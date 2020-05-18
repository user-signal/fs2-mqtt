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

package net.sigusr.mqtt.api

import cats.Show

import scala.concurrent.duration.FiniteDuration

sealed trait ConnectionState
object ConnectionState {
  case object Disconnected extends ConnectionState
  case class Connecting(nextDelay: FiniteDuration, retriesSoFar: Int) extends ConnectionState
  case object Connected extends ConnectionState
  case object SessionStarted extends ConnectionState
  case class Error(error: Errors) extends ConnectionState

  implicit val showTransportStatus: Show[ConnectionState] = Show.show {
    case Disconnected     => "Disconnected"
    case Connecting(_, _) => s"Connecting"
    case Connected        => "Connected"
    case SessionStarted   => "Session started"
    case Error(_)         => "Error"
  }
}
