package net.sigusr.mqtt.impl.net

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import fs2.io.tcp.Socket
import fs2.{Chunk, Stream}
import net.sigusr.mqtt.MonadThrow
import net.sigusr.mqtt.impl.frames.Frame
import scodec.Codec
import scodec.bits.BitVector
import scodec.stream.StreamDecoder

import scala.concurrent.duration.FiniteDuration

trait BrockerConnector[F[_]] {

  def send(frame: Frame): F[Unit]

  def frameStream: Stream[F, Frame]

}

object BrockerConnector {

  //TODO parametrize?
  private val NUM_BYTES = 4096

  def apply[F[_]: MonadThrow: Sync](socket: Socket[F], readTimeout: FiniteDuration, writeTimeout: FiniteDuration, traceMessages: Boolean = false): BrockerConnector[F] = new BrockerConnector[F] {

    private def write(bits: BitVector): F[Unit] =
      socket.write(Chunk.array(bits.toByteArray), Some(writeTimeout))

    override def send(frame: Frame): F[Unit] = for {
      _ <- Sync[F].delay(println(s" → ${Console.GREEN}$frame${Console.RESET}")).whenA(traceMessages)
      _ <- Codec[Frame].encode(frame).fold(
        e => MonadError[F, Throwable].raiseError[Unit](new Exception(e.message)),
        write)
    } yield ()

    def frameStream: Stream[F, Frame] = for {
      frame <- socket.reads(NUM_BYTES).through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
      _ <- Stream.eval(Sync[F].delay(println(s" ← ${Console.YELLOW}$frame${Console.RESET}"))).whenA(traceMessages)
    } yield frame
  }
}
