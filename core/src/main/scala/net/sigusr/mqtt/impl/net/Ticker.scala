package net.sigusr.mqtt.impl.net

import java.util.concurrent.TimeUnit

import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef

import scala.concurrent.duration.FiniteDuration

trait Ticker[F[_]] {

  def reset: F[Unit]

  def cancel: F[Unit]

}

object Ticker {

  def apply[F[_]: Concurrent: Timer](interval: Long, program: F[Unit]): F[Ticker[F]] = for {
    s <- SignallingRef[F, Long](1)
    f <- (Stream.fixedRate(FiniteDuration(1, TimeUnit.SECONDS)) *>
      Stream.eval(s.modify(l => (l + 1, l))))
      .filter(_ % interval == 0).evalMap(_ => program).compile.drain.start
  } yield new Ticker[F] {

    override def reset: F[Unit] = s.set(1)

    override def cancel: F[Unit] = f.cancel
  }
}