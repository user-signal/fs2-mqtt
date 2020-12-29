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

import cats.effect.implicits._
import cats.effect.{Blocker, Concurrent, ContextShift, Fiber, Timer}
import cats.implicits._
import enumeratum.values._
import fs2.concurrent.SignallingRef
import fs2.io.tcp.{Socket, SocketGroup}
import fs2.{Pipe, Stream}
import net.sigusr.mqtt.api.ConnectionFailureReason.TransportError
import net.sigusr.mqtt.api.ConnectionState.{Connected, Connecting, Disconnected, Error}
import net.sigusr.mqtt.api.Errors.ConnectionFailure
import net.sigusr.mqtt.api.{ConnectionState, RetryConfig, TransportConfig}
import net.sigusr.mqtt.impl.frames.Frame
import net.sigusr.mqtt.impl.protocol.Transport.Direction.{In, Out}
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry._
import scodec.Codec
import scodec.stream.{StreamDecoder, StreamEncoder}

import java.net.InetSocketAddress
import scala.collection.immutable
import scala.concurrent.duration._

trait Transport[F[_]] {}

object Transport {

  sealed abstract class Direction(val value: Char, val color: String, val active: Boolean) extends CharEnumEntry
  object Direction extends CharEnum[Direction] {
    case class In(override val active: Boolean) extends Direction('←', Console.YELLOW, active)
    case class Out(override val active: Boolean) extends Direction('→', Console.GREEN, active)

    val values: immutable.IndexedSeq[Direction] = findValues
  }

  private def traceTLS[F[_]: Concurrent](b: Boolean) =
    if (b) Some((m: String) => putStrLn(s"${Console.MAGENTA}[TLS] $m${Console.RESET}")) else None

  private def tracingPipe[F[_]: Concurrent](d: Direction): Pipe[F, Frame, Frame] =
    frames =>
      for {
        frame <- frames
        _ <-
          if (d.active) Stream.eval(putStrLn(s" ${d.value} ${d.color}$frame${Console.RESET}"))
          else Stream.eval(Concurrent[F].unit)
      } yield frame

  private def connect[F[_]: Concurrent: ContextShift: Timer](
      transportConfig: TransportConfig[F],
      stateSignal: SignallingRef[F, ConnectionState],
      closeSignal: SignallingRef[F, Boolean],
      in: Pipe[F, Frame, Unit],
      out: Stream[F, Frame]
  ): F[Fiber[F, Unit]] = {

    def outgoing(socket: Socket[F]): F[Unit] =
      out
        .through(tracingPipe(Out(transportConfig.traceMessages)))
        .through(StreamEncoder.many[Frame](Codec[Frame].asEncoder).toPipeByte)
        .through(socket.writes(transportConfig.writeTimeout))
        .onComplete {
          Stream.eval(stateSignal.set(Disconnected))
        }
        .compile
        .drain

    def incoming(socket: Socket[F]): F[Unit] =
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

    def closeSignalWatcher(socket: Socket[F]) =
      closeSignal.discrete.evalMap(if (_) socket.close else Concurrent[F].pure(())).compile.drain

    def loop(): F[Unit] = {

      val policy: RetryPolicy[F] = RetryConfig.policyOf[F](transportConfig.retryConfig)

      def publishError(err: Throwable, details: RetryDetails): F[Unit] =
        details match {
          case WillDelayAndRetry(nextDelay: FiniteDuration, retriesSoFar: Int, _) =>
            stateSignal.set(Connecting(nextDelay, retriesSoFar))

          case GivingUp(_, _) =>
            stateSignal.set(Error(ConnectionFailure(TransportError(err))))
        }

      def pump(socket: Socket[F]) =
        for {
          _ <- stateSignal.set(Connected)
          _ <- outgoing(socket).race(incoming(socket)).race(closeSignalWatcher(socket))
        } yield ()

      retryingOnAllErrors(policy, publishError) {
        Blocker[F].use { blocker =>
          SocketGroup[F](blocker).use { socketGroup =>
            socketGroup.client[F](new InetSocketAddress(transportConfig.host, transportConfig.port)).use { socket =>
              transportConfig.tlsConfig.fold(pump(socket)) { tlsConfig =>
                tlsConfig
                  .contextOf(blocker)
                  .flatMap(_.client(socket, tlsConfig.tlsParameters, traceTLS(transportConfig.traceMessages)).use(pump))
              }
            }
          }
        }
      } >> stateSignal.get.flatMap {
        case Disconnected => closeSignal.set(false) >> loop()
        case _            => Concurrent[F].pure(())
      }
    }

    loop().start
  }

  def apply[F[_]: Concurrent: ContextShift: Timer](
      transportConfig: TransportConfig[F],
      in: Pipe[F, Frame, Unit],
      out: Stream[F, Frame],
      stateSignal: SignallingRef[F, ConnectionState],
      closeSignal: SignallingRef[F, Boolean]
  ): F[Transport[F]] =
    for {

      _ <- connect(transportConfig, stateSignal, closeSignal, in, out)

    } yield new Transport[F] {}
}
