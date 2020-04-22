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

import cats.effect.Console.io._
import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.implicits._
import fs2.Stream
import fs2.io.tcp.SocketGroup
import net.sigusr.mqtt.impl.net.{BrockerConnector, Connection}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.Random

object LocalPublisher extends IOApp {

  private val random: Stream[IO, Int] = Stream.eval(IO.delay(Math.abs(Random.nextInt()))).repeat

  private def ticks(): Stream[IO, Unit] =
    random >>= { r =>
      val interval = r % 2000 + 1000
      Stream.sleep(FiniteDuration(interval, TimeUnit.MILLISECONDS))
    }

  private def randomMessage(messages: Vector[String]): Stream[IO, String] =
    random >>= (r => Stream.emit(messages(r % messages.length)))

  override def run(args: List[String]): IO[ExitCode] = {
    if (args.length > 2) {
      val topic = args.head
      val messages = args.drop(1).toVector
      Blocker[IO].use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          socketGroup.client[IO](new InetSocketAddress("localhost", 1883)).use { socket =>
            val bc = BrockerConnector[IO](socket, Int.MaxValue.seconds, 3.seconds, traceMessages = true)
            Connection(bc, s"$localPublisher").use { connection =>
              (for {
                m <- ticks().zipRight(randomMessage(messages))
                _ <- Stream.eval(putStrLn(s"Publishing on topic ${Console.CYAN}$topic${Console.RESET} message ${Console.BOLD}$m${Console.RESET}"))
                _ <- Stream.eval_(connection.publish(topic, payload(m)))
              } yield ()).compile.drain
            }
          }
        }
      }.as(ExitCode.Success)
    } else {
      putStrLn(s"${Console.RED}At least a « topic » and one or more « messages » should be provided.${Console.RESET}").as(ExitCode.Error)
    }
  }
}