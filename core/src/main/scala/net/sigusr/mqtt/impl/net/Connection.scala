package net.sigusr.mqtt.impl.net

import cats.effect.implicits._
import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}
import cats.implicits._
import fs2.concurrent.{Queue, SignallingRef}
import fs2.{INothing, Stream}
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce}
import net.sigusr.mqtt.api.{DEFAULT_KEEP_ALIVE, Message, MessageId, QualityOfService, Will}
import net.sigusr.mqtt.impl.frames._
import scodec.bits.ByteVector

trait Connection[F[_]] {

  def disconnect: F[Unit]

  def subscriptions(): Stream[F, Message]

  def subscribe(topics: Vector[(String, QualityOfService)], messageId: MessageId): F[Unit]

  def publish(topic: String, payload: Vector[Byte], qos: QualityOfService = AtMostOnce, messageId: Option[MessageId] = None, retain: Boolean = false): F[Unit]

}

object Connection {

  private val QUEUE_SIZE = 128

  private val zeroId = MessageId(0)


  private def pump[F[_]: Sync](frameStream: Stream[F, Frame], frameQueue: Queue[F, Frame], messageQueue: Queue[F, Message], pingTimer: Ticker[F], stopSignal: SignallingRef[F, Boolean]): Stream[F, INothing] = {
    frameStream.flatMap {
      case PublishFrame(_: Header, topic: String, _: Int, payload: ByteVector) => Stream.eval_(messageQueue.enqueue1(Message(topic, payload.toArray.toVector)))
      case PingRespFrame(_) => Stream.eval_(Sync[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}")))
      case m => Stream.eval_(frameQueue.enqueue1(m))
    }.onComplete(Stream.eval_(stopSignal.set(true)))
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

  def apply[F[_]: Concurrent: Timer: ContextShift](brockerConnector: BrockerConnector[F], clientId: String, keepAlive: Int = DEFAULT_KEEP_ALIVE, cleanSession: Boolean = true, will: Option[Will] = None, user: Option[String] = None, password: Option[String] = None): Resource[F, Connection[F]] = for {
    r <- Resource.make(fromBrockerConnector(brockerConnector, clientId, keepAlive, cleanSession, will, user, password))(_.disconnect)
  } yield r

  private def fromBrockerConnector[F[_]: Concurrent: Timer: ContextShift](brockerConnector: BrockerConnector[F], clientId: String, keepAlive: Int, cleanSession: Boolean, will: Option[Will], user: Option[String], password: Option[String]): F[Connection[F]] = for {
    frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
    messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
    stopSignal <- SignallingRef[F, Boolean](false)
    pingTimer <- Ticker(keepAlive.toLong)
    framePumper <- pump(brockerConnector.frameStream, frameQueue, messageQueue, pingTimer, stopSignal).compile.drain.start
    _ <- brockerConnector.send(connectMessage(clientId, keepAlive, cleanSession, will, user, password))
    _ <- frameQueue.dequeue1 //TODO check
    fib <- pingTimer.ticks.evalMap(_ => brockerConnector.send(PingReqFrame(Header(dup = false, AtMostOnce.value)))).compile.drain.start
  } yield new Connection[F] {

    private def send(frame: Frame): F[Unit] = brockerConnector.send(frame) *> pingTimer.reset

    override val disconnect: F[Unit] = {
      val header = Header(dup = false, AtMostOnce.value)
      val disconnectMessage = DisconnectFrame(header)
      fib.cancel *> framePumper.cancel *> brockerConnector.send(disconnectMessage)
    }

    override val subscriptions: Stream[F, Message] = messageQueue.dequeue.interruptWhen(stopSignal)

    override def subscribe(topics: Vector[(String, QualityOfService)], messageId: MessageId): F[Unit] = {
      val header = Header(dup = false, AtLeastOnce.value)
      val subscribeMessage = SubscribeFrame(header, messageId.identifier, topics.map((v: (String, QualityOfService)) => (v._1, v._2.value)))
      for {
        _ <- send(subscribeMessage)
        _ <- frameQueue.dequeue1 //TODO check
      } yield ()
    }

    override def publish(topic: String, payload: Vector[Byte], qos: QualityOfService, messageId: Option[MessageId], retain: Boolean): F[Unit] = {
      val header = Header(dup = false, qos.value, retain = retain)
      send(PublishFrame(header, topic, messageId.getOrElse(zeroId).identifier, ByteVector(payload)))
    }
  }
}