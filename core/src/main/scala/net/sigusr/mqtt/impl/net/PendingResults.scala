package net.sigusr.mqtt.impl.net

import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, MVar, Ref, Semaphore}
import cats.implicits._

sealed trait Result
object Result {
  case object Empty extends Result
  case class QoS(values: Vector[Int]) extends Result
}

trait PendingResults[F[_]] {

  def add(key: Int, subscriptions: Deferred[F, Result]): F[Unit]

  def remove(key: Int): F[Option[Deferred[F, Result]]]

}

object PendingResults {

  def apply[F[_]: Concurrent]: F[PendingResults[F]] = for {
    mm <- MVar.of[F, Map[Int, Deferred[F, Result]]](Map.empty)
  } yield new PendingResults[F]() {

    override def add(key: Int, subscriptions: Deferred[F, Result]): F[Unit] = for {
      m <- mm.take
      _ <- mm.put(m.updated(key, subscriptions))
    } yield ()

    override def remove(key: Int): F[Option[Deferred[F, Result]]] = for {
      m <- mm.take
      r = m.get(key)
      _ <- mm.put(m.removed(key))
    } yield r
  }
}
