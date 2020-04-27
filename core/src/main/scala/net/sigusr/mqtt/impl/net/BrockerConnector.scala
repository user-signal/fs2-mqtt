package net.sigusr.mqtt.impl.net

import cats.effect.Sync
import cats.implicits._
import enumeratum.values._
import fs2.io.tcp.Socket
import fs2.{Pipe, Stream}
import net.sigusr.mqtt.impl.frames.Frame
import net.sigusr.mqtt.impl.net.BrockerConnector.Direction.{In, Out}
import scodec.Codec
import scodec.stream.{StreamDecoder, StreamEncoder}

import scala.concurrent.duration.FiniteDuration


trait BrockerConnector[F[_]] {

  def frameStream: Stream[F, Frame]

  def outFrameStream: Pipe[F, Frame, Unit]

}

object BrockerConnector {

  sealed abstract class Direction(val value: Char, val color: String) extends CharEnumEntry
  object Direction extends CharEnum[Direction] {
    case object In extends Direction('←', Console.YELLOW)
    case object Out extends Direction('→', Console.GREEN)

    val values: IndexedSeq[Direction] = findValues
  }

  //TODO parametrize?
  private val NUM_BYTES = 4096

  def apply[F[_]: Sync](
    socket: Socket[F],
    readTimeout: FiniteDuration,
    writeTimeout: FiniteDuration,
    traceMessages: Boolean = false
  ): BrockerConnector[F] = new BrockerConnector[F] {

    private def tracingPipe(d: Direction): Pipe[F, Frame, Frame] = frames => for {
      frame <- frames
      _ <- Stream.eval(Sync[F].delay(println(s" ${d.value} ${d.color}$frame${Console.RESET}")).whenA(traceMessages))
    } yield frame

    def outFrameStream: Pipe[F, Frame, Unit] = (frames: Stream[F, Frame]) =>
      frames
        .through(tracingPipe(Out))
        .through(StreamEncoder.many[Frame](Codec[Frame].asEncoder).toPipeByte)
        .through(socket.writes())

    def frameStream: Stream[F, Frame] =
      socket.reads(NUM_BYTES)
        .through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
        .through(tracingPipe(In))
  }
}
