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
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import fs2.concurrent.SignallingRef
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.api.RetryConfig.Custom
import net.sigusr.mqtt.api._
import retry.RetryPolicies

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, SECONDS}

object LocalSubscriber extends IOApp {

  private val stopTopic: String = s"$localSubscriber/stop"
  private val subscribedTopics: Vector[(String, QualityOfService)] = Vector(
    (stopTopic, ExactlyOnce),
    ("AtMostOnce", AtMostOnce),
    ("AtLeastOnce", AtLeastOnce),
    ("ExactlyOnce", ExactlyOnce)
  )

  private val unsubscribedTopics: Vector[String] = Vector("AtMostOnce", "AtLeastOnce", "ExactlyOnce")

  override def run(args: List[String]): IO[ExitCode] = {
    val retryConfig: Custom[IO] = Custom[IO](
      RetryPolicies
        .limitRetries[IO](5)
        .join(RetryPolicies.fullJitter[IO](FiniteDuration(2, SECONDS)))
    )
    val transportConfig =
      TransportConfig[IO](
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
    implicit val console: Console[IO] = Console.make[IO]
    Session[IO](transportConfig, sessionConfig).use { session =>
      SignallingRef[IO, Boolean](false).flatMap { stopSignal =>
          val sessionStatus = session.state.discrete
            .evalMap(logSessionStatus[IO])
            .evalMap(onSessionError[IO])
            .interruptWhen(stopSignal)
            .compile
            .drain
          val subscriber = for {
            s <- session.subscribe(subscribedTopics)
            _ <- s.traverse { p =>
              putStrLn[IO](
                s"Topic ${scala.Console.CYAN}${p._1}${scala.Console.RESET} subscribed with QoS " +
                  s"${scala.Console.CYAN}${p._2.show}${scala.Console.RESET}"
              )
            }
            _ <- IO.sleep(FiniteDuration(23, TimeUnit.SECONDS))
            _ <- session.unsubscribe(unsubscribedTopics)
            _ <-
              putStrLn[IO](s"Topic ${scala.Console.CYAN}${unsubscribedTopics.mkString(", ")}${scala.Console.RESET} unsubscribed")
            _ <- stopSignal.discrete.compile.drain
          } yield ()
          val reader = session.messages.flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
          for {
            _ <- IO.racePair(sessionStatus, subscriber.race(reader))
          } yield ExitCode.Success
        }
    }.handleErrorWith(_ => IO.pure(ExitCode.Error))
  }

  private def processMessages(stopSignal: SignallingRef[IO, Boolean]): Message => Stream[IO, Unit] = {
    case Message(LocalSubscriber.stopTopic, _) => Stream.exec(stopSignal.set(true))
    case Message(topic, payload) =>
      Stream.eval(
        putStrLn[IO](
          s"Topic ${scala.Console.CYAN}$topic${scala.Console.RESET}: " +
            s"${scala.Console.BOLD}${new String(payload.toArray, "UTF-8")}${scala.Console.RESET}"
        )
      )
  }
}
