package net.sigusr.mqtt.impl.net

import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.concurrent.{Queue, SignallingRef}
import fs2.{INothing, Stream}
import net.sigusr.mqtt.api.Message
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.Result.{Empty, QoS}
import scodec.bits.ByteVector

trait Protocol[F[_]] {

  def send: Frame => F[Unit]

  def cancel: F[Unit]

  def messages: Stream[F, Message]

}

object Protocol {

  private val QUEUE_SIZE = 128

  def apply[F[_]: Concurrent: Timer](
    brockerConnector: BrockerConnector[F],
    frameQueue: Queue[F, Frame],
    pendingResults: PendingResults[F],
    keepAlive: Long
  ): F[Protocol[F]] = {
    def protocol(messageQueue: Queue[F, Message], pingTicker: Ticker[F]): Frame => Stream[F, INothing] = {

      case PublishFrame(header: Header, topic: String, messageIdentifier: Int, payload: ByteVector) =>
        header.qos match {
          case AtMostOnce.value =>
            Stream.eval_(messageQueue.enqueue1(Message(topic, payload.toArray.toVector)))
          case AtLeastOnce.value =>
            Stream.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) *>
              Stream.eval_(brockerConnector.send(PubackFrame(Header(), messageIdentifier)) *> pingTicker.reset)
          case ExactlyOnce.value => Stream.empty // Todo
        }

      case PubackFrame(_: Header, messageIdentifier) =>
        Stream.eval_(pendingResults.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(Empty))))

      case PingRespFrame(_) =>
        Stream.eval_(Concurrent[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}")))

      case UnsubackFrame(_: Header, messageIdentifier) =>
        Stream.eval_(pendingResults.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(Empty))))

      case SubackFrame(_: Header, messageIdentifier, topics) =>
        Stream.eval_(pendingResults.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(QoS(topics)))))

      case m => Stream.eval_(frameQueue.enqueue1(m))
    }

    for {
      messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
      stopSignal <- SignallingRef[F, Boolean](false)
      pingTicker <- Ticker(keepAlive, brockerConnector.send(PingReqFrame(Header())))
      protoStream = brockerConnector.frameStream
        .flatMap(protocol(messageQueue, pingTicker))
        .onComplete(Stream.eval(stopSignal.set(true)))
      f <- protoStream.compile.drain.start
    } yield new Protocol[F] {

      override def cancel: F[Unit] = pingTicker.cancel *> f.cancel

      override def send: Frame => F[Unit] = brockerConnector.send(_) *> pingTicker.reset

      override def messages: Stream[F, Message] = messageQueue.dequeue.interruptWhen(stopSignal)
    }
  }
}
