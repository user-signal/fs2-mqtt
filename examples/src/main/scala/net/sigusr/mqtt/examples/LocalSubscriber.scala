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

import java.net.InetSocketAddress

import cats.effect.Console.io._
import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.tcp.SocketGroup
import net.sigusr.mqtt.api.Message
import net.sigusr.mqtt.api.QualityOfService.AtLeastOnce
import net.sigusr.mqtt.impl.net.Errors.ConnectionFailure
import net.sigusr.mqtt.impl.net.{BrockerConnector, Config, Connection}

import scala.concurrent.duration._

object LocalSubscriber extends IOApp {

  val stopTopic: String = s"$localSubscriber/stop"

  override def run(args: List[String]): IO[ExitCode] = {
    val topics = args.toVector
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        socketGroup.client[IO](new InetSocketAddress("localhost", 1883)).use { socket =>
          val bc = BrockerConnector[IO](socket, Int.MaxValue.seconds, 3.seconds, traceMessages = true)
          val config = Config(s"$localSubscriber", user = Some(localSubscriber), password = Some("yolo"))
          Connection(bc, config).use { connection =>
            SignallingRef[IO, Boolean](false).flatMap { stopSignal =>
              val subscriber = for {
                s <- connection.subscribe((stopTopic +: topics) zip Vector.fill(topics.length + 1) { AtLeastOnce })
                _ <- s.traverse { p =>
                  putStrLn(s"Topic ${Console.CYAN}${p._1}${Console.RESET} subscribed with QoS ${Console.CYAN}${p._2.show}${Console.RESET}")
                }
                _ <- IO.sleep(23.seconds)
                topic = topics.take(1)
                _ <- connection.unsubscribe(topic)
                _ <- putStrLn(s"Topic ${Console.CYAN}${topic.mkString(", ")}${Console.RESET} unsubscribed")
                _ <- stopSignal.discrete.compile.drain
              } yield ()
              val reader = connection.messages().flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
              for {
                _ <- IO.race(reader, subscriber)
              } yield ExitCode.Success
            }
          }
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