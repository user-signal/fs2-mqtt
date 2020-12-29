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

package net.sigusr.mqtt.impl

import monocle.Lens
import monocle.macros.GenLens
import scodec.Codec
import scodec.bits._
import scodec.codecs._

package object frames {

  val qosCodec: Codec[Int] = uint2
  val returnCodeCodec: Codec[Int] = uint8
  val messageIdCodec: Codec[Int] = uint16
  val keepAliveCodec: Codec[Int] = uint16

  val remainingLengthCodec = new RemainingLengthCodec
  val stringCodec: Codec[String] = variableSizeBytes(uint16, utf8)
  val bytePaddingCodec: Codec[Unit] = constant(bin"00000000")
  val setDupFlag: Frame => Frame = {
    case f0: PublishFrame     => publishFrame.set(true)(f0)
    case f1: PubrelFrame      => pubrelFrame.set(true)(f1)
    case f2: SubscribeFrame   => subscribeFrame.set(true)(f2)
    case f3: UnsubscribeFrame => unsubscribeFrame.set(true)(f3)
    case f: Frame             => f
  }
  private val publishFrame: Lens[PublishFrame, Boolean] = GenLens[PublishFrame](_.header.dup)
  private val pubrelFrame: Lens[PubrelFrame, Boolean] = GenLens[PubrelFrame](_.header.dup)
  private val subscribeFrame: Lens[SubscribeFrame, Boolean] = GenLens[SubscribeFrame](_.header.dup)
  private val unsubscribeFrame: Lens[UnsubscribeFrame, Boolean] = GenLens[UnsubscribeFrame](_.header.dup)
}
