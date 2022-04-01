package net.sigusr.mqtt.sparkplug

import cats.effect.std.Console
import cats.effect.{Resource, Temporal}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.net.Network
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.api._
import org.eclipse.tahu.protobuf.sparkplug_b.Payload

trait SparkplugBTopic {
  def topic: String
}

object SparkplugBTopic {
  val Namespace = "spBv1.0"
  def apply(topic: String): Either[String, SparkplugBTopic] = topic.split('/') match {
    case Array(Namespace, groupId, messageType, nodeId) => Right(SparkplugBNodeTopic(groupId, messageType, nodeId))
    case Array(Namespace, groupId, messageType, nodeId, deviceId) =>
      Right(SparkplugBDeviceTopic(groupId, messageType, nodeId, deviceId))
    case _ => Left(s"Invalid Sparkplug B topic: $topic")
  }
}

case class SparkplugBNodeTopic(groupId: String, messageType: String, nodeId: String) extends SparkplugBTopic {
  def topic: String = Seq(SparkplugBTopic.Namespace, groupId, messageType, nodeId).mkString("/")
}
case class SparkplugBDeviceTopic(groupId: String, messageType: String, nodeId: String, deviceId: String)
    extends SparkplugBTopic {
  def topic: String = Seq(SparkplugBTopic.Namespace, groupId, messageType, nodeId, deviceId).mkString("/")
}

sealed case class SparkplugBMessage(topic: SparkplugBTopic, payload: Payload)

trait SparkplugBSession[F[_]] {

  def messages: Stream[F, SparkplugBMessage]

  def subscribe(topics: Vector[(SparkplugBTopic, QualityOfService)]): F[Vector[(SparkplugBTopic, QualityOfService)]]

  def unsubscribe(topics: Vector[SparkplugBTopic]): F[Unit]

  def publish(
      topic: SparkplugBTopic,
      payload: Payload,
      qos: QualityOfService = AtMostOnce,
      retain: Boolean = false
  ): F[Unit]

  def state: SignallingRef[F, ConnectionState]

  def publishNodeBirth(groupId: String, nodeId: String, payload: Payload): F[Unit] = publish(
    SparkplugBNodeTopic(groupId, "NBIRTH", nodeId),
    payload
  )
  def publishDeviceBirth(groupId: String, nodeId: String, deviceId: String, payload: Payload): F[Unit] = publish(
    SparkplugBDeviceTopic(groupId, "DBIRTH", nodeId, deviceId),
    payload
  )

  def publishNodeDeath(groupId: String, nodeId: String, payload: Payload): F[Unit] = publish(
    SparkplugBNodeTopic(groupId, "NDEATH", nodeId),
    payload
  )
  def publishDeviceDeath(groupId: String, nodeId: String, deviceId: String, payload: Payload): F[Unit] = publish(
    SparkplugBDeviceTopic(groupId, "DDEATH", nodeId, deviceId),
    payload
  )

}
object SparkplugBSession {
  def apply[F[_]: Temporal: Network: Console](
      transportConfig: TransportConfig[F],
      sessionConfig: SessionConfig
  ): Resource[F, SparkplugBSession[F]] =
    for {
      session <- Session(transportConfig, sessionConfig)
    } yield new SparkplugBSession[F] {
      override def messages: Stream[F, SparkplugBMessage] = session.messages
        .map(m =>
          for {
            topic <- SparkplugBTopic(m.topic).left.map(new IllegalStateException(_))
            payload <- Payload.validate(m.payload.toArray).toEither
          } yield SparkplugBMessage(topic, payload)
        )
        .rethrow

      override def subscribe(
          topics: Vector[(SparkplugBTopic, QualityOfService)]
      ): F[Vector[(SparkplugBTopic, QualityOfService)]] = session
        .subscribe(topics.map { case (t, qos) =>
          (t.topic, qos)
        })
        .map(_.map { case (t, qos) =>
          (SparkplugBTopic.apply(t).getOrElse(throw new IllegalArgumentException(s"Invalid $t")), qos)
        })

      override def unsubscribe(topics: Vector[SparkplugBTopic]): F[Unit] = session.unsubscribe(topics.map(_.topic))

      override def publish(topic: SparkplugBTopic, payload: Payload, qos: QualityOfService, retain: Boolean): F[Unit] =
        session.publish(topic.topic, payload.toByteArray.toVector, qos, retain)

      override def state: SignallingRef[F, ConnectionState] = session.state
    }
}
