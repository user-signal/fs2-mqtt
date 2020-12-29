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

package net.sigusr.mqtt.impl.frames

import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce}
import net.sigusr.mqtt.api.{QualityOfService, SessionConfig}
import scodec.bits.ByteVector

object Builders {

  def subscribeFrame(messageId: Int, topics: Vector[(String, QualityOfService)]): SubscribeFrame =
    SubscribeFrame(
      Header(qos = AtLeastOnce.value),
      messageId,
      topics.map((v: (String, QualityOfService)) => (v._1, v._2.value))
    )

  def unsubscribeFrame(messageId: Int, topics: Vector[String]): UnsubscribeFrame =
    UnsubscribeFrame(Header(qos = AtLeastOnce.value), messageId, topics)

  def connectFrame(config: SessionConfig): ConnectFrame = {
    val header = Header(qos = AtMostOnce.value)
    val retain = config.will.fold(false)(_.retain)
    val qos = config.will.fold(AtMostOnce.value)(_.qos.value)
    val topic = config.will.map(_.topic)
    val message = config.will.map(_.message)
    val variableHeader =
      ConnectVariableHeader(
        config.user.isDefined,
        config.password.isDefined,
        willRetain = retain,
        qos,
        willFlag = config.will.isDefined,
        config.cleanSession,
        config.keepAlive
      )
    ConnectFrame(header, variableHeader, config.clientId, topic, message, config.user, config.password)
  }

  def publishFrame(
      topic: String,
      messageId: Option[Int],
      payload: Vector[Byte],
      qos: QualityOfService,
      retain: Boolean
  ): PublishFrame = {
    val header = Header(dup = false, qos.value, retain = retain)
    PublishFrame(header, topic, messageId, ByteVector(payload))
  }
}
