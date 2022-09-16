package net.sigusr.mqtt.examples

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
 * WITHOUT WARRANTIES OR CONDITTaskNS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.api._
import net.sigusr.mqtt.sparkplug._
import org.eclipse.tahu.protobuf.sparkplug_b.Payload

import scala.concurrent.duration._
import scala.util.Random

object LocalSparkplugBPublisher extends IOApp {

  case class Device(id: String, name: String, signal: Signal)
  case class Signal(value: Double, isCurrent: Boolean)
  val deviceMetrics = ToMetrics.gen[Device]
  val payload: String => Payload = (s: String) =>
    Payload(metrics =
      deviceMetrics
        .toMetrics("foo", Device(s, s, Signal(Random.nextDouble(), Random.nextBoolean())))
    )
  private val random: Stream[IO, Int] = Stream.eval(IO.delay(Math.abs(Random.nextInt()))).repeat
  private val topics =
    Stream(("AtMostOnce", AtMostOnce), ("AtLeastOnce", AtLeastOnce), ("ExactlyOnce", ExactlyOnce)).repeat

  override def run(args: List[String]): IO[ExitCode] =
    if (args.nonEmpty) {
      val nodeId :: deviceId :: messageList = args
      val messages = messageList.toArray.toIndexedSeq
      val transportConfig =
        TransportConfig[IO](
          Host.fromString("localhost").get,
          Port.fromString("1883").get,
          // TLS support looks like
          // 8883,
          // tlsConfig = Some(TLSConfig(TLSContextKind.System)),
          traceMessages = true
        )
      val sessionConfig =
        SessionConfig(s"$localPublisher", user = Some(localPublisher), password = Some("yala"), keepAlive = 5)
      SparkplugBSession[IO](transportConfig, sessionConfig)
        .use { session =>
          val sessionStatus = session.state.discrete
            .evalMap(logSessionStatus[IO])
            .evalMap(onSessionError[IO])
            .compile
            .drain
          val publisher = (for {
            m <- ticks().zipRight(randomMessage(messages).zip(topics))
            message = m._1
            topic = SparkplugBNodeTopic(nodeId, "NCMD", deviceId)
            qos = m._2._2
            _ <- Stream.eval(
              putStrLn[IO](
                s"Publishing on topic ${scala.Console.CYAN}$topic${scala.Console.RESET} with QoS " +
                  s"${scala.Console.CYAN}${qos.show}${scala.Console.RESET} message ${scala.Console.BOLD}$message${scala.Console.RESET}"
              )
            )
            _ <- Stream.eval(session.publish(topic, payload(message), qos))
          } yield ()).compile.drain
          for {
            _ <- IO.race(publisher, sessionStatus)
          } yield ExitCode.Success
        }
        .handleErrorWith(_ => IO.pure(ExitCode.Error))
    } else
      putStrLn[IO](s"${scala.Console.RED}At least one or more « messages » should be provided.${scala.Console.RESET}")
        .as(ExitCode.Error)

  private def ticks(): Stream[IO, Unit] =
    random.flatMap { r =>
      val interval = r % 2000 + 1000
      Stream.sleep[IO](interval.milliseconds)
    }

  private def randomMessage(messages: IndexedSeq[String]): Stream[IO, String] =
    random.flatMap(r => Stream.emit(messages(r % messages.length)))
}
