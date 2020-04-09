package net.sigusr.mqtt.impl.net

import cats.MonadError
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

  def apply[F[_]: MonadThrow](socket: Socket[F], readTimeout: FiniteDuration, writeTimeout: FiniteDuration): BrockerConnector[F] = new BrockerConnector[F] {

    private def write(bits: BitVector): F[Unit] =
      socket.write(Chunk.array(bits.toByteArray), Some(writeTimeout))

    override def send(frame: Frame): F[Unit] = Codec[Frame].encode(frame).fold(
      e => MonadError[F, Throwable].raiseError[Unit](new Exception(e.message)),
      write
    )

    def frameStream: Stream[F, Frame] =
      socket.reads(NUM_BYTES).through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
  }
}
