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
import java.util.concurrent.TimeUnit

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.effect.Console.io._
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.tcp.SocketGroup
import net.sigusr.mqtt.api.Message
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.impl.net.{BrockerConnector, Connection}

import scala.concurrent.duration._

object LocalSubscriber extends IOApp {

  val stopTopic: String = s"$localSubscriber/stop"

  override def run(args: List[String]): IO[ExitCode] = {
    val topics = args.toVector
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        socketGroup.client[IO](new InetSocketAddress("localhost", 1883)).use { socket =>
          val bc = BrockerConnector[IO](socket, Int.MaxValue.seconds, 3.seconds, traceMessages = true)
          Connection(bc, "clientId").use { connection =>
            SignallingRef[IO, Boolean](false).flatMap { stopSignal =>
              val prog1 = connection.subscriptions().flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
              val prog2 = for {
                s <- connection.subscribe((stopTopic +: topics) zip Vector.fill(topics.length + 1) { AtMostOnce })
                _ <- s.traverse { p =>
                  putStrLn(s"Topic ${Console.CYAN}${p._1}${Console.RESET} subscribed with QoS ${Console.CYAN}${p._2.show}${Console.RESET}")
                }
                _ <- IO.sleep(FiniteDuration(5, TimeUnit.SECONDS))
                _ <- connection.publish("yolo", payload("5s"))
                _ <- IO.sleep(FiniteDuration(3, TimeUnit.SECONDS))
                _ <- connection.publish("yolo", payload("3s"))
                _ <- IO.sleep(FiniteDuration(7, TimeUnit.SECONDS))
                _ <- connection.publish("yolo", payload("7s"))
              } yield ()
              for {
                fiber1 <- prog1.start
                _ <- prog2
                _ <- fiber1.join
              } yield ExitCode.Success
            }
          }
        }
      }
    }
  }

  private val payload = (_:String).getBytes("UTF-8").toVector

  private def processMessages(stopSignal: SignallingRef[IO, Boolean])(message: Message): Stream[IO, Unit] = message match {
    case Message(LocalSubscriber.stopTopic, _) => Stream.eval_(stopSignal.set(true))
    case Message(topic, payload) => Stream.eval(IO {
      println(s"[$topic] ${new String(payload.toArray, "UTF-8")}")
    })
  }
}