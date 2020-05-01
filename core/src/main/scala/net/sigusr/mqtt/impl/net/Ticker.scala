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

package net.sigusr.mqtt.impl.net

import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.Stream

import scala.concurrent.duration.FiniteDuration

trait Ticker[F[_]] {

  def reset: F[Unit]

  def cancel: F[Unit]

}

object Ticker {

  def apply[F[_]: Concurrent: Timer](interval: Long, program: F[Unit]): F[Ticker[F]] = for {
    s <- Ref.of[F, Long](1)
    f <- (Stream.fixedRate(FiniteDuration(1, TimeUnit.SECONDS)) *>
      Stream.eval(s.modify(l => (l + 1, l))))
      .filter(_ % interval == 0).evalMap(_ => program).compile.drain.start
  } yield new Ticker[F] {

    override def reset: F[Unit] = s.set(1)

    override def cancel: F[Unit] = f.cancel
  }
}