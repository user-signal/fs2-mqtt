package net.sigusr.mqtt.impl.net

import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.api.{ConnectionFailureReason, DEFAULT_KEEP_ALIVE, Message, ProtocolError, QualityOfService, Will}
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.Builders._
import net.sigusr.mqtt.impl.net.Errors._
import net.sigusr.mqtt.impl.net.Result.QoS

trait Connection[F[_]] {

  def disconnect: F[Unit]

  def subscriptions(): Stream[F, Message]

  def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]]

  def unsubscribe(topics: Vector[String]): F[Unit]

  def publish(topic: String, payload: Vector[Byte], qos: QualityOfService = AtMostOnce, retain: Boolean = false): F[Unit]

}

object Connection {

  private val QUEUE_SIZE = 128

  def apply[F[_]: Concurrent: Timer: ContextShift](
    brockerConnector: BrockerConnector[F],
    clientId: String,
    keepAlive: Int = DEFAULT_KEEP_ALIVE,
    cleanSession: Boolean = true,
    will: Option[Will] = None,
    user: Option[String] = None,
    password: Option[String] = None
  ): Resource[F, Connection[F]] =
    Resource.make(fromBrockerConnector(
      brockerConnector,
      clientId,
      keepAlive,
      cleanSession,
      will,
      user,
      password
    ))(_.disconnect)

  private def checkConnectionAck[F[_]: Sync](f: Frame): F[Unit] = f match {
    case ConnackFrame(_: Header, 0) =>
      Sync[F].unit
    case ConnackFrame(_, returnCode) =>
      ConnectionFailure(ConnectionFailureReason.withValue(returnCode)).raiseError[F, Unit]
    case _ =>
      ProtocolError.raiseError[F, Unit]
  }

  private def fromBrockerConnector[F[_]: Concurrent: Timer: ContextShift](brockerConnector: BrockerConnector[F], clientId: String, keepAlive: Int, cleanSession: Boolean, will: Option[Will], user: Option[String], password: Option[String]): F[Connection[F]] = for {
    frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
    messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
    stopSignal <- SignallingRef[F, Boolean](false)
    subs <- PendingResults[F]
    ids <- IdGenerator[F]
    protocol <- Protocol(brockerConnector, frameQueue, messageQueue, subs, stopSignal, keepAlive.toLong)
    _ <- protocol.send(connectFrame(clientId, keepAlive, cleanSession, will, user, password))
    f <- frameQueue.dequeue1
    _ <- checkConnectionAck(f)
  } yield new Connection[F] {

    override val disconnect: F[Unit] = {
      val disconnectMessage = DisconnectFrame(Header())
      ids.cancel *> protocol.send(disconnectMessage)
    }

    override val subscriptions: Stream[F, Message] = messageQueue.dequeue.interruptWhen(stopSignal)

    override def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]] = {
      for {
        messageId <- ids.next
        d <- Deferred[F, Result]
        _ <- subs.add(messageId, d)
        _ <- protocol.send(subscribeFrame(messageId, topics))
        v <- d.get
        t = v match { case QoS(topics) => topics }
      } yield topics.zip(t).map(p => (p._1._1, QualityOfService.withValue(p._2)))
    }

    override def unsubscribe(topics: Vector[String]): F[Unit] = {
      for {
        messageId <- ids.next
        d <- Deferred[F, Result]
        _ <- subs.add(messageId, d)
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
          _ <- subs.add(messageId, d)
          _ <- protocol.send(publishFrame(topic, Some(messageId), payload, qos, retain))
          _ <- d.get
        } yield ()
      }
    }
  }
}