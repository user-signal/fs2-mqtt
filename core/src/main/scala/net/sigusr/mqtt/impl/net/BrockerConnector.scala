package net.sigusr.mqtt.impl.net

import cats.effect.Sync
import cats.implicits._
import fs2.io.tcp.Socket
import fs2.{Chunk, Pipe, Stream}
import net.sigusr.mqtt.impl.frames.Frame
import net.sigusr.mqtt.impl.net.Errors.DecodingError
import scodec.Codec
import scodec.bits.BitVector
import scodec.stream.{StreamDecoder, StreamEncoder}

import scala.concurrent.duration.FiniteDuration

trait BrockerConnector[F[_]] {

  def send(frame: Frame): F[Unit]

  def frameStream: Stream[F, Frame]

}

object BrockerConnector {

  //TODO parametrize?
  private val NUM_BYTES = 4096

  def apply[F[_]: Sync](socket: Socket[F], readTimeout: FiniteDuration, writeTimeout: FiniteDuration, traceMessages: Boolean = false): BrockerConnector[F] = new BrockerConnector[F] {

    private def write(bits: BitVector): F[Unit] =
      socket.write(Chunk.array(bits.toByteArray), Some(writeTimeout))

    override def send(frame: Frame): F[Unit] = for {
      _ <- Sync[F].delay(println(s" → ${Console.GREEN}$frame${Console.RESET}")).whenA(traceMessages)
      _ <- Codec[Frame].encode(frame).fold(e => DecodingError(e.message).raiseError[F, Unit], write)
    } yield ()

    def outFrameStream: Pipe[F, Frame, Unit] = (frames: Stream[F, Frame]) => for {
      frame <- frames
      _ <- Stream.eval(Sync[F].delay(println(s" → ${Console.GREEN}$frame${Console.RESET}")).whenA(traceMessages))
      _ <- frames.through(StreamEncoder.many[Frame](Codec[Frame].asEncoder).toPipeByte).through(socket.writes())
    } yield ()

    def frameStream: Stream[F, Frame] = for {
      frame <- socket.reads(NUM_BYTES).through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
      _ <- Stream.eval(Sync[F].delay(println(s" ← ${Console.YELLOW}$frame${Console.RESET}"))).whenA(traceMessages)
    } yield frame
  }
}
