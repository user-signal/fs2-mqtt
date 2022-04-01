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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.examples

import cats.effect.std.Console
import cats.implicits._
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import fs2.concurrent.SignallingRef
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.api.RetryConfig.Custom
import net.sigusr.mqtt.api._
import net.sigusr.mqtt.sparkplug._
import retry.RetryPolicies
import zio.duration.Duration
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

object LocalSparkplugBSubscriber extends App {
  private val stopTopic: SparkplugBTopic = SparkplugBNodeTopic("local", "NDEATH", localSubscriber)
  private val otherTopics = Vector(
    (SparkplugBDeviceTopic("local", "NDATA", localSubscriber, "AtMostOnce"), AtMostOnce),
    (SparkplugBDeviceTopic("local", "NDATA", localSubscriber, "AtLeastOnce"), AtLeastOnce),
    (SparkplugBDeviceTopic("local", "NDATA", localSubscriber, "ExactlyOnce"), ExactlyOnce)
  )
  private val subscribedTopics: Vector[(SparkplugBTopic, QualityOfService)] = Vector(
    (stopTopic, ExactlyOnce)
  ) ++ otherTopics

  private val unsubscribedTopics: Vector[SparkplugBTopic] = otherTopics.map(_._1)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val retryConfig: Custom[Task] = Custom[Task](
      RetryPolicies
        .limitRetries[Task](5)
        .join(RetryPolicies.fullJitter[Task](2.seconds))
    )
    val transportConfig =
      TransportConfig[Task](
        Host.fromString("localhost").get,
        Port.fromString("1883").get,
        // TLS support looks like
        // 8883,
        // tlsConfig = Some(TLSConfig(TLSContextKind.System)),
        retryConfig = retryConfig,
        traceMessages = true
      )
    val sessionConfig =
      SessionConfig(
        s"$localSubscriber",
        cleanSession = false,
        user = Some(localSubscriber),
        password = Some("yolo"),
        keepAlive = 5
      )
    implicit val console: Console[Task] = Console.make[Task]
    SparkplugBSession[Task](transportConfig, sessionConfig).use { session =>
      SignallingRef[Task, Boolean](false)
        .flatMap { stopSignal =>
          val sessionStatus = session.state.discrete
            .evalMap(logSessionStatus[Task])
            .evalMap(onSessionError[Task])
            .interruptWhen(stopSignal)
            .compile
            .drain
          val subscriber = for {
            s <- session.subscribe(subscribedTopics)
            _ <- s.traverse { p =>
              putStrLn[Task](
                s"Topic ${scala.Console.CYAN}${p._1}${scala.Console.RESET} subscribed with QoS " +
                  s"${scala.Console.CYAN}${p._2.show}${scala.Console.RESET}"
              )
            }
            _ <- ZIO.sleep(Duration(23, TimeUnit.SECONDS))
            _ <- session.unsubscribe(unsubscribedTopics)
            _ <-
              putStrLn[Task](
                s"Topic ${scala.Console.CYAN}${unsubscribedTopics.mkString(", ")}${scala.Console.RESET} unsubscribed"
              )
            _ <- stopSignal.discrete.compile.drain
          } yield ()
          val reader = session.messages.flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
          for {
            _ <- sessionStatus <&> subscriber.race(reader)
          } yield ()
        }
        .asInstanceOf[Task[Boolean]]
    }
  }.exitCode

  private def processMessages(stopSignal: SignallingRef[Task, Boolean]): SparkplugBMessage => Stream[Task, Unit] = {
    case SparkplugBMessage(LocalSparkplugBSubscriber.stopTopic, _) => Stream.exec(stopSignal.set(true))
    case SparkplugBMessage(topic, payload) =>
      Stream.eval(Task {
        println(
          s"Topic ${scala.Console.CYAN}$topic${scala.Console.RESET}: " +
            s"${scala.Console.BOLD}${new String(payload.toByteArray, "UTF-8")}${scala.Console.RESET}"
        )
      })
  }
}
