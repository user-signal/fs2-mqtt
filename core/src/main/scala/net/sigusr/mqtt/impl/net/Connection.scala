package net.sigusr.mqtt.impl.net

import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.api.{DEFAULT_KEEP_ALIVE, Message, MessageId, QualityOfService, Will}
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.Builders._

trait Connection[F[_]] {

  def disconnect: F[Unit]

  def subscriptions(): Stream[F, Message]

  def subscribe(topics: Vector[(String, QualityOfService)], messageId: MessageId): F[Unit]

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

  private def fromBrockerConnector[F[_]: Concurrent: Timer: ContextShift](brockerConnector: BrockerConnector[F], clientId: String, keepAlive: Int, cleanSession: Boolean, will: Option[Will], user: Option[String], password: Option[String]): F[Connection[F]] = for {
    frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
    messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
    stopSignal <- SignallingRef[F, Boolean](false)
    ids <- IdGenerator[F]
    pingTicker <- Ticker(keepAlive.toLong, brockerConnector.send(pingReqFrame))
    framePumper <- Pumper(brockerConnector.frameStream, frameQueue, messageQueue, stopSignal)
    _ <- brockerConnector.send(connectFrame(clientId, keepAlive, cleanSession, will, user, password))
    _ <- frameQueue.dequeue1 //TODO check
  } yield new Connection[F] {

    private def send(frame: Frame): F[Unit] = brockerConnector.send(frame) *> pingTicker.reset

    override val disconnect: F[Unit] = {
      val header = Header(dup = false, AtMostOnce.value)
      val disconnectMessage = DisconnectFrame(header)
      ids.cancel *> pingTicker.cancel *> framePumper.cancel *> brockerConnector.send(disconnectMessage)
    }

    override val subscriptions: Stream[F, Message] = messageQueue.dequeue.interruptWhen(stopSignal)

    override def subscribe(topics: Vector[(String, QualityOfService)], messageId: MessageId): F[Unit] = {
      for {
        messageId <- ids.next
        _ <- send(subscribeFrame(topics, messageId))
        _ <- frameQueue.dequeue1
      } yield ()
    }

    override def publish(topic: String, payload: Vector[Byte], qos: QualityOfService, retain: Boolean): F[Unit] = {
      send(publishFrame(topic, payload, qos, retain))
    }
  }
}