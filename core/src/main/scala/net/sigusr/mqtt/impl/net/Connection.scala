package net.sigusr.mqtt.impl.net

import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.implicits._
import fs2.Stream
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.api.{DEFAULT_KEEP_ALIVE, Message, QualityOfService, Will}
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.Builders._
import net.sigusr.mqtt.impl.net.Result.QoS

sealed case class Config(
  clientId: String,
  keepAlive: Int = DEFAULT_KEEP_ALIVE,
  cleanSession: Boolean = true,
  will: Option[Will] = None,
  user: Option[String] = None,
  password: Option[String] = None
)

trait Connection[F[_]] {

  def disconnect: F[Unit]

  def messages(): Stream[F, Message]

  def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]]

  def unsubscribe(topics: Vector[String]): F[Unit]

  def publish(topic: String, payload: Vector[Byte], qos: QualityOfService = AtMostOnce, retain: Boolean = false): F[Unit]

}

object Connection {

  def apply[F[_]: Concurrent: Timer: ContextShift](
    brockerConnector: BrockerConnector[F],
    config: Config
  ): Resource[F, Connection[F]] =
    Resource.make(fromBrockerConnector(
      brockerConnector,
      config
  ))(_.disconnect)

  private def fromBrockerConnector[F[_]: Concurrent: Timer: ContextShift](brockerConnector: BrockerConnector[F], config: Config): F[Connection[F]] = for {
    pendingResults <- AtomicMap[F, Int, Deferred[F, Result]]
    ids <- IdGenerator[F]
    protocol <- Protocol(brockerConnector, pendingResults, config.keepAlive.toLong)
    _ <- protocol.connect(config)
  } yield new Connection[F] {

    override val disconnect: F[Unit] = {
      val disconnectMessage = DisconnectFrame(Header())
      ids.cancel *> protocol.send(disconnectMessage)
    }

    override val messages: Stream[F, Message] = protocol.messages

    override def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]] = {
      for {
        messageId <- ids.next
        d <- Deferred[F, Result]
        _ <- pendingResults.add(messageId, d)
        _ <- protocol.send(subscribeFrame(messageId, topics))
        v <- d.get
        t = v match { case QoS(topics) => topics }
      } yield topics.zip(t).map(p => (p._1._1, QualityOfService.withValue(p._2)))
    }

    override def unsubscribe(topics: Vector[String]): F[Unit] = {
      for {
        messageId <- ids.next
        d <- Deferred[F, Result]
        _ <- pendingResults.add(messageId, d)
        _ <- protocol.send(unsubscribeFrame(messageId, topics))
        _ <- d.get
      } yield ()
    }

    override def publish(topic: String, payload: Vector[Byte], qos: QualityOfService, retain: Boolean): F[Unit] = {
      qos match {
        case QualityOfService.AtMostOnce =>
          protocol.send(publishFrame(topic, None, payload, qos, retain))
        case QualityOfService.AtLeastOnce | QualityOfService.ExactlyOnce => for {
          messageId <- ids.next
          d <- Deferred[F, Result]
          _ <- pendingResults.add(messageId, d)
          _ <- protocol.send(publishFrame(topic, Some(messageId), payload, qos, retain))
          _ <- d.get
        } yield ()
      }
    }
  }
}