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

package net.sigusr.mqtt.api

import cats.Show

import scala.collection.immutable

enum QualityOfService(val value: Int):
  case AtMostOnce extends QualityOfService(0)
  case AtLeastOnce extends QualityOfService(1)
  case ExactlyOnce extends QualityOfService(2)

object QualityOfService {
  implicit val showQualityOfService: Show[QualityOfService] = Show.show {
    case AtMostOnce  => "at most once"
    case AtLeastOnce => "at least once"
    case ExactlyOnce => "exactly once"
  }
}
