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
import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.implicits._
import enumeratum.values._
import fs2.concurrent.{Queue, SignallingRef}
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

trait Transport[F[_]] {

  def inFrameStream: Stream[F, Frame]

  def outFrameStream: Pipe[F, Frame, Unit]

}

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

  private def publishError[F[_]: Concurrent](
      statusSignal: SignallingRef[F, ConnectionStatus]
  )(err: Throwable, details: RetryDetails): F[Unit] =
    details match {
      case WillDelayAndRetry(nextDelay: FiniteDuration, retriesSoFar: Int, _) =>
        statusSignal.set(Connecting(nextDelay, retriesSoFar))

      case GivingUp(_, _) =>
        statusSignal.set(Error(ConnectionFailure(TransportError(err))))
    }

  private def connect[F[_]: Concurrent: ContextShift](
      transportConfig: TransportConfig,
      statusSignal: SignallingRef[F, ConnectionStatus],
      in: Queue[F, Frame],
      out: Queue[F, Frame]
  ): F[Unit] = {

    def incomming(socket: Socket[F]) =
      out.dequeue
        .through(tracingPipe(Out(transportConfig.traceMessages)))
        .through(StreamEncoder.many[Frame](Codec[Frame].asEncoder).toPipeByte)
        .through(socket.writes(transportConfig.writeTimeout))
        .onComplete {
          Stream.eval(statusSignal.set(Disconnected))
        }
        .compile
        .drain

    def outgoing(socket: Socket[F]) =
      socket
        .reads(transportConfig.numReadBytes, transportConfig.readTimeout)
        .through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
        .through(tracingPipe(In(transportConfig.traceMessages)))
        .through(in.enqueue)
        .onComplete {
          Stream.eval(statusSignal.set(Disconnected))
        }
        .compile
        .drain

    Blocker[F].use { blocker =>
      SocketGroup[F](blocker).use { socketGroup =>
        socketGroup.client[F](new InetSocketAddress(transportConfig.host, transportConfig.port)).use { socket =>
          for {
            _ <- statusSignal.set(Connected)
            _ <- Concurrent[F].race(incomming(socket), outgoing(socket))
          } yield ()
        }
      }
    }
  }

  def apply[F[_]: Concurrent: ContextShift: Timer](
      transportConfig: TransportConfig,
      stateSignal: SignallingRef[F, ConnectionStatus]
  ): F[Transport[F]] = {

    val policy = RetryPolicies
      .limitRetries[F](transportConfig.maxRetries)
      .join(RetryPolicies.fibonacciBackoff(transportConfig.baseDelay))
    for {
      in <- Queue.bounded[F, Frame](QUEUE_SIZE)
      out <- Queue.bounded[F, Frame](QUEUE_SIZE)
      _ <- retryingOnAllErrors[Unit](policy, publishError[F](stateSignal))(
        connect(transportConfig, stateSignal, in, out)
      ).start
    } yield new Transport[F] {

      def outFrameStream: Pipe[F, Frame, Unit] = out.enqueue

      def inFrameStream: Stream[F, Frame] = in.dequeue

    }
  }
}
