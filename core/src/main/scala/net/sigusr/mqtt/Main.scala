package net.sigusr.mqtt

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import fs2.io.tcp.SocketGroup
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.impl.frames.{ConnectFrame, ConnectVariableHeader, DisconnectFrame, Header, SubscribeFrame}
import net.sigusr.mqtt.impl.net.BitVectorSocket

import scala.concurrent.duration._

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        BitVectorSocket[IO]("localhost", 1883, Int.MaxValue.seconds, 3.seconds, socketGroup).use { client =>
          val header = Header(dup = false, AtMostOnce.value)
          val connectVariableHeader = ConnectVariableHeader(userNameFlag = false, passwordFlag = false, willRetain = true, ExactlyOnce.value, willFlag = false, cleanSession = false, 128)
          val connectMessage = ConnectFrame(header, connectVariableHeader, "clientId", None, None, None, None)
          val disconnectMessage = DisconnectFrame(header)
          val topics = Vector(("topic0", AtMostOnce.value), ("topic1", AtLeastOnce.value), ("topic2", ExactlyOnce.value))
          val subscribeFrame = SubscribeFrame(header, 3, topics)
          for {
            _ <- IO(println(connectMessage))
            _ <- client.send(connectMessage)
            f <- client.receive()
            _ <- IO(println(f))
            _ <- IO(println(subscribeFrame))
            _ <- client.send(subscribeFrame)
            f <- client.receive()
            f <- client.receive()
            _ <- IO(println(f))
            _ <- IO(println(disconnectMessage))
            _ <- client.send(disconnectMessage)
          } yield ExitCode.Success
        }
      }
    }
  }
}
