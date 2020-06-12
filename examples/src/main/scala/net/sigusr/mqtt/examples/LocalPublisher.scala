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

package net.sigusr.mqtt.examples

import cats.effect.ExitCode
import cats.implicits._
import fs2.Stream
import monix.eval.{Task, TaskApp}
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.api._

import scala.concurrent.duration._
import scala.util.Random

object LocalPublisher extends TaskApp {

  private val random: Stream[Task, Int] = Stream.eval(Task.delay(Math.abs(Random.nextInt()))).repeat

  private def ticks(): Stream[Task, Unit] =
    random >>= { r =>
      val interval = r % 2000 + 1000
      Stream.sleep[Task](interval.milliseconds)
    }

  private def randomMessage(messages: Vector[String]): Stream[Task, String] =
    random >>= (r => Stream.emit(messages(r % messages.length)))

  private val topics =
    Stream(("AtMostOnce", AtMostOnce), ("AtLeastOnce", AtLeastOnce), ("ExactlyOnce", ExactlyOnce)).repeat

  override def run(args: List[String]): Task[ExitCode] =
    if (args.nonEmpty) {
      val messages = args.toVector
      val transportConfig =
        TransportConfig[Task](
          "localhost",
          1883,
          // TLS support looks like
          // 8883,
          // tlsConfig = Some(TLSConfig(TLSContextKind.System)),
          traceMessages = true
        )
      val sessionConfig =
        SessionConfig(s"$localPublisher", user = Some(localPublisher), password = Some("yala"), keepAlive = 5)
      Session[Task](transportConfig, sessionConfig)
        .use { session =>
          val sessionStatus = session.state.discrete
            .evalMap(logSessionStatus[Task])
            .evalMap(onSessionError[Task])
            .compile
            .drain
          val publisher = (for {
            m <- ticks().zipRight(randomMessage(messages).zip(topics))
            message = m._1
            topic = m._2._1
            qos = m._2._2
            _ <- Stream.eval(
              putStrLn[Task](
                s"Publishing on topic ${Console.CYAN}$topic${Console.RESET} with QoS " +
                  s"${Console.CYAN}${qos.show}${Console.RESET} message ${Console.BOLD}$message${Console.RESET}"
              )
            )
            _ <- Stream.eval(session.publish(topic, payload(message), qos))
          } yield ()).compile.drain
          for {
            _ <- Task.race(publisher, sessionStatus)
          } yield ExitCode.Success
        }
        .handleErrorWith(_ => Task.pure(ExitCode.Error))
    } else
      putStrLn[Task](s"${Console.RED}At least one or more « messages » should be provided.${Console.RESET}")
        .as(ExitCode.Error)
}
