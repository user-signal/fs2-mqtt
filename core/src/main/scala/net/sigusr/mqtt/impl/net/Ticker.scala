package net.sigusr.mqtt.impl.net

import java.util.concurrent.TimeUnit

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef

import scala.concurrent.duration.FiniteDuration

trait Ticker[F[_]] {

  def reset: F[Unit]

  def ticks: Stream[F, Unit]

}

object Ticker {

  def apply[F[_]: Concurrent: Timer](interval: Long): F[Ticker[F]] = for {
    s <- SignallingRef[F, Long](1)
  } yield new Ticker[F] {

    override def reset: F[Unit] = s.set(1)

    override def ticks: Stream[F, Unit] = (for {
      _ <- Stream.fixedRate(FiniteDuration(1, TimeUnit.SECONDS))
      t <- Stream.eval(s.modify(l => (l + 1, l)))
    } yield t).filter(_ % interval == 0).map(_ => ())
  }
}