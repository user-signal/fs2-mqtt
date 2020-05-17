/*
 * Copyright 2020 Frédéric Cabestre
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

package net.sigusr.mqtt.impl.protocol

import java.net.InetSocketAddress

import cats.effect.implicits._
import cats.effect.{Blocker, Concurrent, ContextShift, Fiber, Timer}
import cats.implicits._
import enumeratum.values._
import fs2.concurrent.SignallingRef
import fs2.io.tcp.{Socket, SocketGroup}
import fs2.{Pipe, Stream}
import net.sigusr.mqtt.api.ConnectionFailureReason.TransportError
import net.sigusr.mqtt.api.ConnectionStatus
import net.sigusr.mqtt.api.ConnectionStatus.{Connected, Connecting, Disconnected, Error}
import net.sigusr.mqtt.api.Errors.ConnectionFailure
import net.sigusr.mqtt.impl.frames.Frame
import net.sigusr.mqtt.impl.protocol.Transport.Direction.{In, Out}
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry._
import scodec.Codec
import scodec.stream.{StreamDecoder, StreamEncoder}

import scala.concurrent.duration._

sealed case class TransportConfig(
    host: String,
    port: Int,
    readTimeout: Option[FiniteDuration] = None,
    writeTimeout: Option[FiniteDuration] = None,
    numReadBytes: Int = 4096,
    maxRetries: Int = 5,
    baseDelay: FiniteDuration = 2.seconds,
    traceMessages: Boolean = false
)

trait Transport[F[_]] {}

object Transport {

  sealed abstract class Direction(val value: Char, val color: String, val active: Boolean) extends CharEnumEntry
  object Direction extends CharEnum[Direction] {
    case class In(override val active: Boolean) extends Direction('←', Console.YELLOW, active)
    case class Out(override val active: Boolean) extends Direction('→', Console.GREEN, active)

    val values: IndexedSeq[Direction] = findValues
  }

  private def tracingPipe[F[_]: Concurrent: ContextShift](d: Direction): Pipe[F, Frame, Frame] =
    frames =>
      for {
        frame <- frames
        _ <- Stream.eval(putStrLn(s" ${d.value} ${d.color}$frame${Console.RESET}")).whenA(d.active)
      } yield frame

  private def connect[F[_]: Concurrent: ContextShift: Timer](
      transportConfig: TransportConfig,
      stateSignal: SignallingRef[F, ConnectionStatus],
      in: Pipe[F, Frame, Unit],
      out: Stream[F, Frame]
  ): F[Fiber[F, Unit]] = {

    def publishError(
        stateSignal: SignallingRef[F, ConnectionStatus]
    )(err: Throwable, details: RetryDetails): F[Unit] =
      details match {
        case WillDelayAndRetry(nextDelay: FiniteDuration, retriesSoFar: Int, _) =>
          stateSignal.set(Connecting(nextDelay, retriesSoFar))

        case GivingUp(_, _) =>
          stateSignal.set(Error(ConnectionFailure(TransportError(err))))
      }

    def outgoing(socket: Socket[F]) =
      out
        .through(tracingPipe(Out(transportConfig.traceMessages)))
        .through(StreamEncoder.many[Frame](Codec[Frame].asEncoder).toPipeByte)
        .through(socket.writes(transportConfig.writeTimeout))
        .onComplete {
          Stream.eval(stateSignal.set(Disconnected))
        }
        .compile
        .drain

    def incoming(socket: Socket[F]) =
      socket
        .reads(transportConfig.numReadBytes, transportConfig.readTimeout)
        .through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
        .through(tracingPipe(In(transportConfig.traceMessages)))
        .through(in)
        .onComplete {
          Stream.eval(stateSignal.set(Disconnected))
        }
        .compile
        .drain

    def loop(): F[Unit] = {

      val policy: RetryPolicy[F] = RetryPolicies
        .limitRetries[F](transportConfig.maxRetries)
        .join(RetryPolicies.fibonacciBackoff(transportConfig.baseDelay))

      retryingOnAllErrors(policy, publishError(stateSignal)) {
        Blocker[F].use { blocker =>
          SocketGroup[F](blocker).use { socketGroup =>
            socketGroup.client[F](new InetSocketAddress(transportConfig.host, transportConfig.port)).use { socket =>
              stateSignal.set(Connected) >> Concurrent[F].race(outgoing(socket), incoming(socket))
            }
          }
        }
      } >> stateSignal.get.flatMap {
        case Disconnected => loop()
        case _            => Concurrent[F].pure(())
      }
    }

    loop().start
  }

  def apply[F[_]: Concurrent: ContextShift: Timer](
      transportConfig: TransportConfig,
      in: Pipe[F, Frame, Unit],
      out: Stream[F, Frame],
      stateSignal: SignallingRef[F, ConnectionStatus]
  ): F[Transport[F]] =
    for {

      _ <- connect(transportConfig, stateSignal, in, out)

    } yield new Transport[F] {}
}
