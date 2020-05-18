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

import net.sigusr.mqtt.SpecUtils.makeRandomByteVector
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import org.specs2.mutable.Specification
import scodec.bits.ByteVector

class SetDupFlagSpec extends Specification {

  "The 'setDupFlag' utility function" should {

    val header = Header(dup = false, AtLeastOnce.value)
    val dupHeader = Header(dup = true, AtLeastOnce.value)

    "Set the dup flag for PublishFrame" in {
      val topic = "a/b"
      val payload = ByteVector(makeRandomByteVector(256))
      val publishFrame = PublishFrame(header, topic, Some(10), payload)

      val duped = setDupFlag(publishFrame).asInstanceOf[PublishFrame]

      duped.header should_=== dupHeader
      duped.topic should_=== topic
      duped.payload should_=== payload
      duped.messageIdentifier should_=== Some(10)

    }

    "Set the dup flag for PubrelFrame" in {
      val pubrelFrame = PubrelFrame(header, 10)

      val duped = setDupFlag(pubrelFrame).asInstanceOf[PubrelFrame]

      duped.header should_=== dupHeader
      duped.messageIdentifier should_=== 10

    }

    "Set the dup flag for SubscribeFrame" in {
      val header = Header(dup = false, AtLeastOnce.value)
      val topics = Vector(
        ("topic0", AtMostOnce.value),
        ("topic1", AtLeastOnce.value),
        ("topic2", ExactlyOnce.value)
      )
      val subscribeFrame = SubscribeFrame(header, 3, topics)
      val duped = setDupFlag(subscribeFrame).asInstanceOf[SubscribeFrame]

      duped.header should_=== dupHeader
      duped.messageIdentifier should_=== 3
      duped.topics should_=== topics
    }

    "Set the dup flag for UnsubscribeFrame" in {
      val topics = Vector("topic0", "topic1")
      val unsubscribeFrame =
        UnsubscribeFrame(header, 10, topics)

      val duped = setDupFlag(unsubscribeFrame).asInstanceOf[UnsubscribeFrame]

      duped.header should_=== dupHeader
      duped.messageIdentifier should_=== 10
      duped.topics should_=== topics
    }
  }
}
