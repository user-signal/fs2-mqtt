package net.sigusr.mqtt.impl.net

import java.net.InetSocketAddress

import cats.effect.implicits._
import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.implicits._
import fs2.concurrent.Queue
import fs2.io.tcp.{Socket, SocketGroup}
import fs2.{Chunk, Stream}
import net.sigusr.mqtt.MonadThrow
import net.sigusr.mqtt.impl.frames.Frame
import scodec.Codec
import scodec.bits.BitVector
import scodec.stream.StreamDecoder

import scala.concurrent.duration.FiniteDuration

trait BitVectorSocket[F[_]] {

  def send(frame: Frame): F[Unit]

  def receive(): F[Frame]

  protected def terminate: F[Unit]

}

object BitVectorSocket {

  private val NUM_BYTES = 4096
  private val QUEUE_SIZE = 128

  def apply[F[_]: Concurrent: ContextShift](host: String, port: Int, readTimeout: FiniteDuration, writeTimeout: FiniteDuration, sg: SocketGroup): Resource[F, BitVectorSocket[F]] =
    for {
      s <- sg.client[F](new InetSocketAddress(host, port))
      r <- Resource.make(BitVectorSocket.fromSocket[F](s, readTimeout, writeTimeout))(_.terminate)
    } yield r

  def frameStream[F[_]: MonadThrow](socket: Socket[F]): Stream[F, Frame] =
    socket.reads(NUM_BYTES).through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)

  def fromSocket[F[_]: Sync: MonadThrow: Concurrent](socket: Socket[F], readTimeout: FiniteDuration, writeTimeout: FiniteDuration): F[BitVectorSocket[F]] = for {
    queue <- Queue.bounded[F, Frame](QUEUE_SIZE)
    fib <- frameStream(socket).repeat.through(queue.enqueue).compile.drain.attempt.flatMap {
      case Left(e) => Concurrent[F].delay(e.printStackTrace())
      case Right(a) => a.pure[F]
    }.start
  } yield new BitVectorSocket[F] {

    private def write(bits: BitVector): F[Unit] =
      socket.write(Chunk.array(bits.toByteArray), Some(writeTimeout))

    override def send(frame: Frame): F[Unit] = Codec[Frame].encode(frame).fold(
      e => Sync[F].raiseError[Unit](new Exception(e.message)),
      write
    )

    override def receive(): F[Frame] = queue.dequeue1

    override protected def terminate: F[Unit] = fib.cancel
  }
}
