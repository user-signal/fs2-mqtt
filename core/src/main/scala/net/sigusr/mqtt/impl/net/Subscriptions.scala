package net.sigusr.mqtt.impl.net

import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._

trait Subscriptions[F[_]] {

  def add(key: Int, subscriptions: Deferred[F, Vector[Int]]): F[Unit]

  def remove(key: Int): F[Option[Deferred[F, Vector[Int]]]]

}

object Subscriptions {

  def apply[F[_]: Concurrent]: F[Subscriptions[F]] = for {
    map <- Ref.of[F, Map[Int, Deferred[F, Vector[Int]]]](Map.empty)
    sem <- Semaphore[F](1)
  } yield new Subscriptions[F]() {

    override def add(key: Int, subscriptions: Deferred[F, Vector[Int]]): F[Unit] = for {
      _ <- sem.acquire
      _ <- map.update(m => m.updated(key, subscriptions))
      _ <- sem.release
    } yield ()

    override def remove(key: Int): F[Option[Deferred[F, Vector[Int]]]] = for {
      _ <- sem.acquire
      m <- map.get
      s = m.get(key)
      _ <- map.modify(m => (m.removed(key), m))
      _ <- sem.release
    } yield s
  }
}
