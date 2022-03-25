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

import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.syntax.all._

trait IdGenerator[F[_]] {
  def next: F[Int]
}

object IdGenerator {

  def apply[F[_] : Concurrent](): F[IdGenerator[F]] =
    for {
      r <- Ref[F].of(0)
    } yield new IdGenerator[F] {
      override def next: F[Int] =
        r.modify(i => ((i + 1) % 65535, i + 1))
    }
}
