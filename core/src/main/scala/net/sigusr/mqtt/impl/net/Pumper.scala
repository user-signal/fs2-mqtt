package net.sigusr.mqtt.impl.net

import cats.effect.implicits._
import cats.effect.{Concurrent, Fiber}
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import net.sigusr.mqtt.api.Message
import net.sigusr.mqtt.impl.frames.{Frame, Header, PingRespFrame, PublishFrame}
import scodec.bits.ByteVector

trait Pumper[F[_]] {}

object Pumper {
  def apply[F[_]: Concurrent](
    frameStream: Stream[F, Frame],
    frameQueue: Queue[F, Frame],
    messageQueue: Queue[F, Message],
    stopSignal: SignallingRef[F, Boolean]
  ): F[Fiber[F, Unit]] = {
    frameStream.flatMap {
      case PublishFrame(_: Header, topic: String, _: Int, payload: ByteVector) => Stream.eval_(messageQueue.enqueue1(Message(topic, payload.toArray.toVector)))
      case PingRespFrame(_) => Stream.eval_(Concurrent[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}")))
      case m => Stream.eval_(frameQueue.enqueue1(m))
    }.onComplete(Stream.eval_(stopSignal.set(true))).compile.drain.start
  }
}
