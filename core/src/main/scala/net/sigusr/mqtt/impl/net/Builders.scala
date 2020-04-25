package net.sigusr.mqtt.impl.net

import net.sigusr.mqtt.api.QualityOfService
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce}
import net.sigusr.mqtt.impl.frames._
import scodec.bits.ByteVector

object Builders {

  private val ZERO_ID = 0

  private[net] def subscribeFrame(messageId: Int, topics: Vector[(String, QualityOfService)]) = {
    SubscribeFrame(Header(qos = AtLeastOnce.value), messageId, topics.map((v: (String, QualityOfService)) => (v._1, v._2.value)))
  }

  private[net] def unsubscribeFrame(messageId: Int, topics: Vector[String]) = {
    UnsubscribeFrame(Header(qos = AtLeastOnce.value), messageId, topics)
  }

  private[net] def connectFrame(config: Config): ConnectFrame = {
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

  private[net] def publishFrame(topic: String, messageId: Option[Int], payload: Vector[Byte], qos: QualityOfService, retain: Boolean) = {
    val header = Header(dup = false, qos.value, retain = retain)
    PublishFrame(header, topic, messageId.getOrElse(ZERO_ID), ByteVector(payload))
  }
}
