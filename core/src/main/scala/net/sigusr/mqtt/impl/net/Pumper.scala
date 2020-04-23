package net.sigusr.mqtt.impl.net

import cats.effect.implicits._
import cats.effect.{Concurrent, Fiber}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import net.sigusr.mqtt.api.Message
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.Result.{Empty, QoS}
import scodec.bits.ByteVector

trait Pumper[F[_]] {}

object Pumper {
  def apply[F[_]: Concurrent](
    brockerConnector: BrockerConnector[F],
    frameQueue: Queue[F, Frame],
    messageQueue: Queue[F, Message],
    subs: PendingResults[F],
    stopSignal: SignallingRef[F, Boolean]
  ): F[Fiber[F, Unit]] = {
    brockerConnector.frameStream.flatMap {
      case PublishFrame(header: Header, topic: String, messageIdentifier: Int, payload: ByteVector) =>
        header.qos match {
          case AtMostOnce.value =>
            Stream.eval_(messageQueue.enqueue1(Message(topic, payload.toArray.toVector)))
          case AtLeastOnce.value =>
            Stream.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) *>
              Stream.eval_(brockerConnector.send(PubackFrame(Header(), messageIdentifier)))
          case ExactlyOnce.value => Stream.empty // Todo
        }

      case PubackFrame(_: Header, messageIdentifier) =>
        Stream.eval_(subs.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(Empty))))

      case PingRespFrame(_) =>
        Stream.eval_(Concurrent[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}")))

      case UnsubackFrame(_: Header, messageIdentifier) =>
        Stream.eval_(subs.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(Empty))))

      case SubackFrame(_: Header, messageIdentifier, topics) =>
        Stream.eval_(subs.remove(messageIdentifier) >>=
          (_.fold(Concurrent[F].pure(()))(_.complete(QoS(topics)))))

      case m => Stream.eval_(frameQueue.enqueue1(m))

    }.onComplete(Stream.eval_(stopSignal.set(true))).compile.drain.start
  }
}
