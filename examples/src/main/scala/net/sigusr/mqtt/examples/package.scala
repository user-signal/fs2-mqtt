/*
 * Copyright 2014 Frédéric Cabestre
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

package net.sigusr.mqtt

import cats.effect.Sync
import cats.implicits._
import net.sigusr.mqtt.api.ConnectionState
import net.sigusr.mqtt.api.ConnectionState.{Connected, Connecting, Disconnected, Error, SessionStarted}
import net.sigusr.mqtt.api.Errors.{ConnectionFailure, ProtocolError}

package object examples {
  val localSubscriber: String = "Local-Subscriber"
  val localPublisher: String = "Local-Publisher"

  val payload: String => Vector[Byte] = (_: String).getBytes("UTF-8").toVector

  def logSessionStatus[F[_]: Sync]: ConnectionState => F[ConnectionState] =
    s =>
      (s match {
        case Error(ConnectionFailure(reason)) =>
          putStrLn(s"${Console.RED}${reason.show}${Console.RESET}")
        case Error(ProtocolError) =>
          putStrLn(s"${Console.RED}Ṕrotocol error${Console.RESET}")
        case Disconnected =>
          putStrLn(s"${Console.BLUE}Transport disconnected${Console.RESET}")
        case Connecting(nextDelay, retriesSoFar) =>
          putStrLn(
            s"${Console.BLUE}Transport connecting. $retriesSoFar attempt(s) so far, next attempt in $nextDelay ${Console.RESET}"
          )
        case Connected =>
          putStrLn(s"${Console.BLUE}Transport connected${Console.RESET}")
        case SessionStarted =>
          putStrLn(s"${Console.BLUE}Session started${Console.RESET}")
      }) >> Sync[F].pure(s)

  def putStrLn[F[_]: Sync](s: String): F[Unit] = Sync[F].delay(println(s))

  def onSessionError[F[_]: Sync]: ConnectionState => F[Unit] = {
    case Error(e) => Sync[F].raiseError(e)
    case _        => Sync[F].pure(())
  }
}
