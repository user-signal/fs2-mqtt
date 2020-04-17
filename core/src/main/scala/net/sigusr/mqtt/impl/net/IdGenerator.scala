package net.sigusr.mqtt.impl.net

import cats.effect.implicits._
import cats.effect.{Concurrent, Fiber}
import cats.implicits._
import fs2.concurrent.Queue
import fs2.{Pure, Stream}

trait IdGenerator[F[_]] {

  def next: F[Int]

  def cancel: F[Unit]

}

object IdGenerator {

  private def idQueue[F[_]: Concurrent](q: Queue[F, Int]): F[Fiber[F, Unit]] = {
    def go(v: Int): Stream[Pure, Int] = v match {
      case 65535 => Stream.emit(0) ++ go(1)
      case _ => Stream.emit(v) ++ go(v + 1)
    }
    go(0).through(q.enqueue(_)).compile.drain.start
  }

  def apply[F[_]: Concurrent]: F[IdGenerator[F]] = for {
    q <- Queue.bounded[F, Int](2)
    f <- idQueue[F](q)
  } yield new IdGenerator[F] {

    override def next: F[Int] = q.dequeue1

    override def cancel: F[Unit] = f.cancel
  }
}