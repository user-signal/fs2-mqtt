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

import net.sigusr.mqtt.api.ConnectionFailureReason

import scala.util.control.NoStackTrace

trait Errors extends NoStackTrace

object Errors {
  case object InternalError extends Errors
  case class DecodingError(message: String) extends Errors
  case class ConnectionFailure(reason: ConnectionFailureReason) extends Errors
}
