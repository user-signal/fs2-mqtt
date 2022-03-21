/*
 * Copyright 2020 Frédéric Cabestre
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.impl.protocol

import cats.effect.implicits._
import cats.effect.std.Queue
import cats.effect.{Concurrent, Fiber}
import cats.implicits._
import fs2.{Pure, Stream}

trait IdGenerator[F[_]] {

  def next: F[Int]

  def cancel: F[Unit]

}

object IdGenerator {

  private def idQueue[F[_]: Concurrent](start: Int, q: Queue[F, Int]): F[Fiber[F, Throwable, Unit]] = {
    def go(v: Int): Stream[Pure, Int] =
      v match {
        case 65535 => Stream.emit(1) ++ go(2)
        case _     => Stream.emit(v) ++ go(v + 1)
      }
    go(start).enqueueUnterminated(q).compile.drain.start
  }

  def apply[F[_]: Concurrent](start: Int): F[IdGenerator[F]] =
    for {
      q <- Queue.bounded[F, Int](2)
      f <- idQueue[F](start, q)
    } yield new IdGenerator[F] {

      override def next: F[Int] = q.take

      override def cancel: F[Unit] = f.cancel
    }
}
