package net.sigusr.mqtt.impl.net

import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
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
    mm <- Ref.of[F, Map[Int, Deferred[F, Result]]](Map.empty)
    sem <- Semaphore[F](1)
  } yield new PendingResults[F]() {

    override def add(key: Int, subscriptions: Deferred[F, Result]): F[Unit] = for {
      _ <- sem.acquire
      _ <- mm.update(m => m.updated(key, subscriptions))
      _ <- sem.release
    } yield ()

    override def remove(key: Int): F[Option[Deferred[F, Result]]] = for {
      _ <- sem.acquire
      m <- mm.get
      s = m.get(key)
      _ <- mm.modify(m => (m.removed(key), m))
      _ <- sem.release
    } yield s
  }
}
