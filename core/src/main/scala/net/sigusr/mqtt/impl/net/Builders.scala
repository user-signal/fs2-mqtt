package net.sigusr.mqtt.impl.net

import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce}
import net.sigusr.mqtt.api.{QualityOfService, Will}
import net.sigusr.mqtt.impl.frames._
import scodec.bits.ByteVector

object Builders {

  private val ZERO_ID = 0

  private [net] def subscribeFrame(messageId: Int, topics: Vector[(String, QualityOfService)]) = {
    val header = Header(dup = false, AtLeastOnce.value)
    SubscribeFrame(header, messageId, topics.map((v: (String, QualityOfService)) => (v._1, v._2.value)))
  }

  private [net] def unsubscribeFrame(messageId: Int, topics: Vector[String]) = {
    val header = Header(dup = false, AtLeastOnce.value)
    UnsubscribeFrame(header, messageId, topics)
  }

  private [net] def connectFrame(clientId: String, keepAlive: Int, cleanSession: Boolean, will: Option[Will], user: Option[String], password: Option[String]): ConnectFrame = {
    val header = Header(dup = false, AtMostOnce.value)
    val retain = will.fold(false)(_.retain)
    val qos = will.fold(AtMostOnce.value)(_.qos.value)
    val topic = will.map(_.topic)
    val message = will.map(_.message)
    val variableHeader = ConnectVariableHeader(user.isDefined, password.isDefined, willRetain = retain, qos, willFlag = will.isDefined, cleanSession, keepAlive)
    ConnectFrame(header, variableHeader, clientId, topic, message, user, password)
  }

  private [net] def publishFrame(topic: String, payload: Vector[Byte], qos: QualityOfService, retain: Boolean) = {
    val header = Header(dup = false, qos.value, retain = retain)
    PublishFrame(header, topic, ZERO_ID, ByteVector(payload))
  }

  private [net] val pingReqFrame = PingReqFrame(Header(dup = false, AtMostOnce.value))
}
