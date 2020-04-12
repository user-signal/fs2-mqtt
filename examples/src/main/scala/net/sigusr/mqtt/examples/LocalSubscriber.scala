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

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.tcp.SocketGroup
import net.sigusr.mqtt.api.Message
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.impl.net.BrockerConnector
import net.sigusr.mqtt.impl.protocol.Connection

import scala.concurrent.duration._

object LocalSubscriber extends IOApp {

  val stopTopic: String = s"$localSubscriber/stop"

  override def run(args: List[String]): IO[ExitCode] = {
    val topics = args.toVector
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        socketGroup.client[IO](new InetSocketAddress("localhost", 1883)).use { socket =>
          val bc = BrockerConnector[IO](socket, Int.MaxValue.seconds, 3.seconds, true)
          Connection(bc, "clientId").use { connection =>
            for {
              stopSignal <- SignallingRef[IO, Boolean](false)
              _ <- connection.subscribe((stopTopic +: topics) zip Vector.fill(topics.length + 1) { AtMostOnce }, 1)
              _ <- connection.subscriptions().flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
            } yield ExitCode.Success
          }
        }
      }
    }
  }

  private def processMessages(stopSignal: SignallingRef[IO, Boolean])(message: Message): Stream[IO, Unit] = message match {
    case Message(LocalSubscriber.stopTopic, _) => Stream.eval_(stopSignal.set(true))
    case Message(topic, payload) => Stream.eval(IO {
      println(s"[$topic] ${new String(payload.toArray, "UTF-8")}")
    })
  }
}