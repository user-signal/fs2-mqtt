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

import scala.collection.immutable
import scala.util.control.NoStackTrace

sealed trait Errors extends NoStackTrace

object Errors {
  case class ConnectionFailure(reason: ConnectionFailureReason) extends Errors

  case object ProtocolError extends Errors
}

enum ConnectionFailureReason(val value: Int):
  case TransportError(reason: Throwable) extends ConnectionFailureReason(0)
  case BadProtocolVersion extends ConnectionFailureReason(1)
  case IdentifierRejected extends ConnectionFailureReason(2)
  case ServerUnavailable extends ConnectionFailureReason(3)
  case BadUserNameOrPassword extends ConnectionFailureReason(4)
  case NotAuthorized extends ConnectionFailureReason(5)

object ConnectionFailureReason {
  implicit val showConnectionFailureReason: Show[ConnectionFailureReason] = Show.show {
    case TransportError(reason) => s"Transport error: ${reason.getMessage}"
    case BadProtocolVersion     => "Bad protocol version"
    case IdentifierRejected     => "Identifier rejected"
    case ServerUnavailable      => "Server unavailable"
    case BadUserNameOrPassword  => "Bad user name or password"
    case NotAuthorized          => "Not authorized"
  }
}
