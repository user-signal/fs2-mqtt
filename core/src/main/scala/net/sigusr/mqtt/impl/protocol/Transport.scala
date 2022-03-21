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
import cats.effect.std.Console
import cats.effect.{Concurrent, Fiber, Temporal}
import cats.implicits._
import com.comcast.ip4s.SocketAddress
import fs2.io.net.{Network, Socket}
import fs2.{Pipe, Stream}
import net.sigusr.impl.protocol.Direction
import net.sigusr.impl.protocol.Direction.{In, Out}
import net.sigusr.mqtt.api.ConnectionFailureReason.TransportError
import net.sigusr.mqtt.api.ConnectionState.{Connected, Connecting, Disconnected, Error}
import net.sigusr.mqtt.api.Errors.ConnectionFailure
import net.sigusr.mqtt.api.{RetryConfig, TransportConfig}
import net.sigusr.mqtt.impl.frames.Frame
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry._
import scodec.Codec
import scodec.stream.{StreamDecoder, StreamEncoder}

import scala.concurrent.duration._

trait Transport[F[_]] {}

object Transport {

  private def traceTLS[F[_] : Console](b: Boolean): Option[(=> String) => F[Unit]] =
    if (b) Some(m => Console[F].println(s"${scala.Console.MAGENTA}[TLS] $m${scala.Console.RESET}")) else None

  private def tracingPipe[F[_]: Concurrent : Console](d: Direction): Pipe[F, Frame, Frame] =
    frames =>
      for {
        frame <- frames
        _ <-
          if (d.active) Stream.eval(Console[F].println(s" ${d.value} ${d.color}$frame${scala.Console.RESET}"))
          else Stream.eval(Concurrent[F].unit)
      } yield frame

  private def connect[F[_]: Temporal: Network: Console](
      transportConfig: TransportConfig[F],
      connector: TransportConnector[F]
  ): F[Fiber[F, Throwable, Unit]] = {

    def outgoing(socket: Socket[F]): F[Unit] =
      connector.out
        .through(tracingPipe(Out(transportConfig.traceMessages)))
        .through(StreamEncoder.many[Frame](Codec[Frame].asEncoder).toPipeByte)
        .through(socket.writes) // TODO either use or remove these config values (transportConfig.writeTimeout)
        .onComplete {
          Stream.eval(connector.stateSignal.set(Disconnected))
        }
        .compile
        .drain

    def incoming(socket: Socket[F]): F[Unit] =
      socket
        .reads // TODO either use or remove these config values (transportConfig.numReadBytes, transportConfig.readTimeout)
        .through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
        .through(tracingPipe(In(transportConfig.traceMessages)))
        .through(connector.in)
        .onComplete {
          Stream.eval(connector.stateSignal.set(Disconnected))
        }
        .compile
        .drain

    def closeSignalWatcher: F[Unit] =
      connector.closeSignal.discrete.compile.drain // TODO evalMap(if (_) socket.close else Concurrent[F].pure(()))

    def loop(): F[Unit] = {

      val policy: RetryPolicy[F] = RetryConfig.policyOf[F](transportConfig.retryConfig)

      def publishError(err: Throwable, details: RetryDetails): F[Unit] =
        details match {
          case WillDelayAndRetry(nextDelay: FiniteDuration, retriesSoFar: Int, _) =>
            connector.stateSignal.set(Connecting(nextDelay, retriesSoFar))

          case GivingUp(_, _) =>
            connector.stateSignal.set(Error(ConnectionFailure(TransportError(err))))
        }

      def pump(socket: Socket[F]): F[Unit] =
        for {
          _ <- connector.stateSignal.set(Connected)
          _ <- outgoing(socket).race(incoming(socket)).race(closeSignalWatcher)
        } yield ()



      retryingOnAllErrors(policy, publishError) {
        Network[F].client(SocketAddress(transportConfig.host, transportConfig.port)).use{ socket =>
          transportConfig.tlsConfig.fold(pump(socket)) { tlsConfig =>
            tlsConfig
              .contextOf
              .flatMap{ tlsContext =>
                val builder = tlsContext.clientBuilder(socket).withParameters(tlsConfig.tlsParameters)
                traceTLS(transportConfig.traceMessages).fold(builder)(builder.withLogging)
                  .build
                  .use(pump)
              }
          }
        }
      } >> connector.stateSignal.get.flatMap {
        case Disconnected => connector.closeSignal.set(false) >> loop()
        case _            => Concurrent[F].unit
      }
    }

    loop().start
  }

  def apply[F[_]: Temporal: Network : Console](
      transportConfig: TransportConfig[F]
  )(connector: TransportConnector[F]): F[Transport[F]] =
    for {

      _ <- connect(transportConfig, connector)

    } yield new Transport[F] {}
}
