package net.sigusr.mqtt.impl.protocol

import cats.effect.implicits._
import cats.effect.{Concurrent, ContextShift, Resource}
import cats.implicits._
import fs2.concurrent.Queue
import fs2.{INothing, Stream}
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce}
import net.sigusr.mqtt.api.{DEFAULT_KEEP_ALIVE, Message, MessageId, QualityOfService, Will}
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.BrockerConnector
import scodec.bits.ByteVector

trait Connection[F[_]] {

  def disconnect: F[Unit]

  def subscriptions(): Stream[F, Message]

  def subscribe(topics: Vector[(String, QualityOfService)], messageId: MessageId): F[Unit]

}

object Connection {

  private val QUEUE_SIZE = 128

  private def pump[F[_]](frameStream: Stream[F, Frame], frameQueue: Queue[F, Frame], messageQueue: Queue[F, Message]): Stream[F, INothing] = {
    frameStream.flatMap {
      case PublishFrame(_: Header, topic: String, _: Int, payload: ByteVector) => Stream.eval_(messageQueue.enqueue1(Message(topic, payload.toArray.toVector)))
      case m => Stream.eval_(frameQueue.enqueue1(m))
    }
  }

  private def connectMessage(clientId: String, keepAlive: Int, cleanSession: Boolean, will: Option[Will], user: Option[String], password: Option[String]): ConnectFrame = {
    val header = Header(dup = false, AtMostOnce.value)
    val retain = will.fold(false)(_.retain)
    val qos = will.fold(AtMostOnce.value)(_.qos.value)
    val topic = will.map(_.topic)
    val message = will.map(_.message)
    val variableHeader = ConnectVariableHeader(user.isDefined, password.isDefined, willRetain = retain, qos, willFlag = will.isDefined, cleanSession, keepAlive)
    ConnectFrame(header, variableHeader, clientId, topic, message, user, password)
  }

  def apply[F[_]: Concurrent: ContextShift](brockerConnector: BrockerConnector[F], clientId: String, keepAlive: Int = DEFAULT_KEEP_ALIVE, cleanSession: Boolean = true, will: Option[Will] = None, user: Option[String] = None, password: Option[String] = None): Resource[F, Connection[F]] = for
    {
    r <- Resource.make(fromBrockerConnector(brockerConnector, clientId, keepAlive, cleanSession, will, user, password))(_.disconnect)
  } yield r

  private def fromBrockerConnector[F[_] : Concurrent : ContextShift](brockerConnector: BrockerConnector[F], clientId: String, keepAlive: Int, cleanSession: Boolean, will: Option[Will], user: Option[String], password: Option[String]): F[Connection[F]] = {
    for {
      frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
      messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
      fib <- pump(brockerConnector.frameStream, frameQueue, messageQueue).compile.drain.start
      _ <- brockerConnector.send(connectMessage(clientId, keepAlive, cleanSession, will, user, password))
      _ <- frameQueue.dequeue1 //TODO check
    } yield new Connection[F] {

      override def disconnect: F[Unit] = {
        val header = Header(dup = false, AtMostOnce.value)
        val disconnectMessage = DisconnectFrame(header)
        fib.cancel *> brockerConnector.send(disconnectMessage)
      }

      override def subscriptions(): Stream[F, Message] = messageQueue.dequeue

      override def subscribe(topics: Vector[(String, QualityOfService)], messageId: MessageId): F[Unit] = {
        val header = Header(dup = false, AtLeastOnce.value)
        val subscribeMessage = SubscribeFrame(header, messageId.identifier, topics.map((v: (String, QualityOfService)) => (v._1, v._2.value)))
        for {
          _ <- brockerConnector.send(subscribeMessage)
          _ <- frameQueue.dequeue1 //TODO check
        } yield ()
      }
    }
  }
}