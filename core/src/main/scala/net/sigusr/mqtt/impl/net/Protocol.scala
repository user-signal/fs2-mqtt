package net.sigusr.mqtt.impl.net

import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.concurrent.{Queue, SignallingRef}
import fs2.{INothing, Pipe, Stream}
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.api.{ConnectionFailureReason, Message, ProtocolError}
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.Builders.connectFrame
import net.sigusr.mqtt.impl.net.Errors.ConnectionFailure
import net.sigusr.mqtt.impl.net.Result.{Empty, QoS}
import scodec.bits.ByteVector

trait Protocol[F[_]] {

  def connect(config: Config): F[Unit]

  def send: Frame => F[Unit]

  def cancel: F[Unit]

  def messages: Stream[F, Message]

}

object Protocol {

  private val QUEUE_SIZE = 128

  def apply[F[_]: Concurrent: Timer](
    brockerConnector: BrockerConnector[F],
    pendingResults: AtomicMap[F, Int, Deferred[F, Result]],
    keepAlive: Long
  ): F[Protocol[F]] = {

    def inboundMessagesInterpreter(
      messageQueue: Queue[F, Message],
      frameQueue: Queue[F, Frame],
      inFlightOutBound: AtomicMap[F, Int, Frame],
      connected: Deferred[F, Int]
    ): Frame => Stream[F, INothing] = {

      case PublishFrame(header: Header, topic: String, messageIdentifier: Int, payload: ByteVector) =>
        header.qos match {
          case AtMostOnce.value =>
            Stream.eval_(messageQueue.enqueue1(Message(topic, payload.toArray.toVector)))
          case AtLeastOnce.value =>
            Stream.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) *>
              Stream.eval_(frameQueue.enqueue1(PubackFrame(Header(), messageIdentifier)))
          case ExactlyOnce.value => Stream.empty // Todo
        }

      case PubackFrame(_: Header, messageIdentifier) =>
        Stream.eval(inFlightOutBound.remove(messageIdentifier)) *>
          Stream.eval_(pendingResults.remove(messageIdentifier) >>=
            (_.fold(Concurrent[F].pure(()))(_.complete(Empty))))

      case PingRespFrame(_: Header) =>
        Stream.eval_(Concurrent[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}")))

      case UnsubackFrame(_: Header, messageIdentifier) =>
        Stream.eval_(pendingResults.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(Empty))))

      case SubackFrame(_: Header, messageIdentifier, topics) =>
        Stream.eval_(pendingResults.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(QoS(topics)))))

      case ConnackFrame(_: Header, returnCode) =>
        Stream.eval_(connected.complete(returnCode))

      case _ =>
        Stream.raiseError[F](ProtocolError)
    }

    def outboundMessagesInterpreter(
      inFlightOutBound: AtomicMap[F, Int, Frame],
    ): Frame => Stream[F, Frame] = {
      case m @ PublishFrame(header: Header, _, messageIdentifier, _) =>
        Stream.eval(
          if (header.qos != AtMostOnce.value)
            inFlightOutBound.add(messageIdentifier, m)
          else Concurrent[F].pure[Unit]()) >> Stream.emit(m)
      case m => Stream.emit(m)
    }

    for {
      connected <- Deferred[F, Int]
      messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
      frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
      stopSignal <- SignallingRef[F, Boolean](false)
      pingTicker <- Ticker(keepAlive, frameQueue.enqueue1(PingReqFrame(Header())))
      inFlightOutBound <- AtomicMap[F, Int, Frame]

      outbound <- frameQueue.dequeue
        .flatMap(Stream.eval(pingTicker.reset) *> Stream.emit(_))
        .flatMap(outboundMessagesInterpreter(inFlightOutBound))
        .through(brockerConnector.outFrameStream)
        .compile.drain.start

      inbound <- brockerConnector.frameStream
        .flatMap(inboundMessagesInterpreter(messageQueue, frameQueue, inFlightOutBound, connected))
        .onComplete(Stream.eval(stopSignal.set(true)))
        .compile.drain.start

    } yield new Protocol[F] {

      override def cancel: F[Unit] = pingTicker.cancel *> outbound.cancel *> inbound.cancel

      override def send: Frame => F[Unit] = frameQueue.enqueue1

      override def messages: Stream[F, Message] = messageQueue.dequeue.interruptWhen(stopSignal)

      override def connect(config: Config): F[Unit] = for {
        _ <- send(connectFrame(config))
        r <- connected.get
      } yield if (r == 0) () else throw ConnectionFailure(ConnectionFailureReason.withValue(r))
    }
  }
}
