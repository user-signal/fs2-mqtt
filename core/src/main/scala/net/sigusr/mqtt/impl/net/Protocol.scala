package net.sigusr.mqtt.impl.net

import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
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
    ): Frame => F[Unit] = {

      case PublishFrame(header: Header, topic: String, messageIdentifier: Int, payload: ByteVector) =>
        header.qos match {
          case AtMostOnce.value =>
            messageQueue.enqueue1(Message(topic, payload.toArray.toVector))
          case AtLeastOnce.value =>
            messageQueue.enqueue1(Message(topic, payload.toArray.toVector)) >>
              frameQueue.enqueue1(PubackFrame(Header(), messageIdentifier))
          case ExactlyOnce.value => Concurrent[F].pure() // Todo
        }

      case PubackFrame(_: Header, messageIdentifier) =>
        inFlightOutBound.remove(messageIdentifier) >>
          pendingResults.remove(messageIdentifier) >>=
            (_.fold(Concurrent[F].pure(()))(_.complete(Empty)))

/*
      case PubrecFrame(header, messageIdentifier) =>
        val pubrelFrame = PubrelFrame(header.copy(qos = 1), messageIdentifier)
        Sequence(Seq(
          RemoveSentInFlightFrame(messageIdentifier),
          StoreSentInFlightFrame(messageIdentifier.identifier, PubrelFrame.dupLens.set(pubrelFrame)(true)),
          SendToNetwork(pubrelFrame)))
      case PubrelFrame(header, messageIdentifier) =>
        Sequence(Seq(
          RemoveRecvInFlightFrameId(messageIdentifier),
          SendToNetwork(PubcompFrame(header.copy(qos = 0), messageIdentifier))))
      case PubcompFrame(_, messageId) =>
        Sequence(Seq(
          RemoveSentInFlightFrame(messageId),
          SendToClient(Published(messageId))))

*/
      case PingRespFrame(_: Header) =>
        Concurrent[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}"))

      case UnsubackFrame(_: Header, messageIdentifier) =>
        pendingResults.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(Empty)))

      case SubackFrame(_: Header, messageIdentifier, topics) =>
        pendingResults.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(QoS(topics))))

      case ConnackFrame(_: Header, returnCode) =>
        connected.complete(returnCode)

      case _ =>
        ProtocolError.raiseError[F, Unit]
    }

    def outboundMessagesInterpreter(
      inFlightOutBound: AtomicMap[F, Int, Frame],
    ): Frame => F[Frame] = {
      case m @ PublishFrame(header: Header, _, messageIdentifier, _) =>
        if (header.qos != AtMostOnce.value)
            inFlightOutBound.add(messageIdentifier, m) >> Concurrent[F].pure(m)
        else Concurrent[F].pure(m)
      case m => Concurrent[F].pure(m)
    }

    for {
      connected <- Deferred[F, Int]
      messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
      frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
      stopSignal <- SignallingRef[F, Boolean](false)
      pingTicker <- Ticker(keepAlive, frameQueue.enqueue1(PingReqFrame(Header())))
      inFlightOutBound <- AtomicMap[F, Int, Frame]

      outbound <- frameQueue.dequeue
        .evalMap(pingTicker.reset *> outboundMessagesInterpreter(inFlightOutBound)(_))
        .through(brockerConnector.outFrameStream)
        .compile.drain.start

      inbound <- brockerConnector.frameStream
        .evalMap(inboundMessagesInterpreter(messageQueue, frameQueue, inFlightOutBound, connected))
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
