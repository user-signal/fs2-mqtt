package net.sigusr.mqtt.impl.net

import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.concurrent.{Queue, SignallingRef}
import fs2.{INothing, Pipe, Pull, Stream}
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
      ): Pipe[F, Frame, Unit] = {

      def loop(s: Stream[F, Frame], inFlightInBound: Set[Int]): Pull[F, INothing, Unit] = s.pull.uncons1.flatMap {
        case Some((hd, tl)) => hd match {

          case PublishFrame(header: Header, topic: String, messageIdentifier: Int, payload: ByteVector) =>
            header.qos match {
              case AtMostOnce.value =>
                Pull.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) >>
                  loop(tl, inFlightInBound)
              case AtLeastOnce.value =>
                Pull.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) >>
                  Pull.eval(frameQueue.enqueue1(PubackFrame(Header(), messageIdentifier))) >>
                  loop(tl, inFlightInBound)
              case ExactlyOnce.value =>
                loop(tl, inFlightInBound) // Todo
            }

          case PubackFrame(_: Header, messageIdentifier) =>
            Pull.eval(inFlightOutBound.remove(messageIdentifier)) >>
              Pull.eval(pendingResults.remove(messageIdentifier) >>=
                (_.fold(Concurrent[F].pure(()))(_.complete(Empty)))) >>
              loop(tl, inFlightInBound)

          case PingRespFrame(_: Header) =>
            Pull.eval(Concurrent[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}"))) >>
              loop(tl, inFlightInBound)

          case UnsubackFrame(_: Header, messageIdentifier) =>
            Pull.eval(pendingResults.remove(messageIdentifier) >>=
              (_.fold(Concurrent[F].pure(()))(_.complete(Empty)))) >>
              loop(tl, inFlightInBound)

          case SubackFrame(_: Header, messageIdentifier, topics) =>
            Pull.eval(pendingResults.remove(messageIdentifier) >>=
              (_.fold(Concurrent[F].pure(()))(_.complete(QoS(topics))))) >>
              loop(tl, inFlightInBound)

          case ConnackFrame(_: Header, returnCode) =>
            Pull.eval(connected.complete(returnCode)) >>
              loop(tl, inFlightInBound)

          case _ =>
            Pull.raiseError[F](ProtocolError)
        }

        case None => Pull.done
      }
      in => loop(in, Set.empty[Int]).stream
    }

    def outboundMessagesInterpreter(
      inFlightOutBound: AtomicMap[F, Int, Frame],
    ): Frame => Stream[F, Frame] = {
      case m @ PublishFrame(header: Header, _, messageIdentifier, _) =>
        Stream.eval(
          if (header.qos != AtMostOnce.value)
            inFlightOutBound.add(messageIdentifier, m)
          else Concurrent[F].pure[Unit](())) >> Stream.emit(m)
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
        .flatMap(Stream.eval(pingTicker.reset) >> Stream.emit(_))
        .flatMap(outboundMessagesInterpreter(inFlightOutBound))
        .through(brockerConnector.outFrameStream)
        .compile.drain.start

      inbound <- brockerConnector.frameStream
        .through(inboundMessagesInterpreter(messageQueue, frameQueue, inFlightOutBound, connected))
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
