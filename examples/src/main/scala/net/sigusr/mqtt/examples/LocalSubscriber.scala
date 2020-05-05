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

import cats.effect.Console.io._
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import net.sigusr.mqtt.api.Errors.ConnectionFailure
import net.sigusr.mqtt.api.QualityOfService
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.impl.net.{BrokerConnector, Config, Connection, Message}

import scala.concurrent.duration._

object LocalSubscriber extends IOApp {

  val stopTopic: String = s"$localSubscriber/stop"
  val subscribedTopics: Vector[(String, QualityOfService)] = Vector(
    (stopTopic, ExactlyOnce),
    ("AtMostOnce", AtMostOnce),
    ("AtLeastOnce", AtLeastOnce),
    ("ExactlyOnce", ExactlyOnce)
  )

  val unsubscribedTopics: Vector[String] = Vector("AtMostOnce", "AtLeastOnce", "ExactlyOnce")

  override def run(args: List[String]): IO[ExitCode] = {
    BrokerConnector[IO]("localhost", 1883, Some(Int.MaxValue.seconds), Some(3.seconds), traceMessages = true).use { bc =>
      val config = Config(s"$localSubscriber", user = Some(localSubscriber), password = Some("yolo"))
      Connection(bc, config).use { connection =>
        SignallingRef[IO, Boolean](false).flatMap { stopSignal =>
          val subscriber = for {
            s <- connection.subscribe(subscribedTopics)
            _ <- s.traverse { p =>
              putStrLn(s"Topic ${Console.CYAN}${p._1}${Console.RESET} subscribed with QoS ${Console.CYAN}${p._2.show}${Console.RESET}")
            }
            _ <- IO.sleep(23.seconds)
            _ <- connection.unsubscribe(unsubscribedTopics)
            _ <- putStrLn(s"Topic ${Console.CYAN}${unsubscribedTopics.mkString(", ")}${Console.RESET} unsubscribed")
            _ <- stopSignal.discrete.compile.drain
          } yield ()
          val reader = connection.messages().flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
          for {
            _ <- IO.race(reader, subscriber)
          } yield ExitCode.Success
        }
      }
    }
  }.handleErrorWith {
    case ConnectionFailure(reason) =>
      putStrLn(s"Connection failure: ${Console.RED}${reason.show}${Console.RESET}").as(ExitCode.Error)
  }

  private def processMessages(stopSignal: SignallingRef[IO, Boolean])(message: Message): Stream[IO, Unit] = message match {
    case Message(LocalSubscriber.stopTopic, _) => Stream.eval_(stopSignal.set(true))
    case Message(topic, payload) => Stream.eval(IO {
      println(s"Topic ${Console.CYAN}$topic${Console.RESET}: ${Console.BOLD}${new String(payload.toArray, "UTF-8")}${Console.RESET}")
    })
  }
}