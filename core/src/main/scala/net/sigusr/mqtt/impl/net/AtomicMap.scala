package net.sigusr.mqtt.impl.net

import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.implicits._

trait AtomicMap[F[_], K, V] {

  def update(key: K, result: V): F[Unit]

  def remove(key: K): F[Option[V]]

}

object AtomicMap {

  def apply[F[_]: Concurrent, K, V]: F[AtomicMap[F, K, V]] = for {

    mm <- Ref.of[F, Map[K, V]](Map.empty)

  } yield new AtomicMap[F, K, V]() {

    override def update(key: K, result: V): F[Unit] = mm.update(m => m.updated(key, result))

    override def remove(key: K): F[Option[V]] = mm.modify(m => (m.removed(key), m.get(key)))
  }
}
