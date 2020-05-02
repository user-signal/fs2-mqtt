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

package net.sigusr.mqtt.impl.net

import cats.effect.Sync
import cats.implicits._
import enumeratum.values._
import fs2.io.tcp.Socket
import fs2.{ Pipe, Stream }
import net.sigusr.mqtt.impl.frames.Frame
import net.sigusr.mqtt.impl.net.BrokerConnector.Direction.{ In, Out }
import scodec.Codec
import scodec.stream.{ StreamDecoder, StreamEncoder }

import scala.concurrent.duration.FiniteDuration

trait BrokerConnector[F[_]] {

  def inFrameStream: Stream[F, Frame]

  def outFrameStream: Pipe[F, Frame, Unit]

}

object BrokerConnector {

  sealed abstract class Direction(val value: Char, val color: String) extends CharEnumEntry
  object Direction extends CharEnum[Direction] {
    case object In extends Direction('←', Console.YELLOW)
    case object Out extends Direction('→', Console.GREEN)

    val values: IndexedSeq[Direction] = findValues
  }

  //TODO parametrize?
  private val NUM_BYTES = 4096

  def apply[F[_]: Sync](
    socket: Socket[F],
    readTimeout: FiniteDuration,
    writeTimeout: FiniteDuration,
    traceMessages: Boolean = false): BrokerConnector[F] = new BrokerConnector[F] {

    private def tracingPipe(d: Direction): Pipe[F, Frame, Frame] = frames => for {
      frame <- frames
      _ <- Stream.eval(Sync[F].delay(println(s" ${d.value} ${d.color}$frame${Console.RESET}")).whenA(traceMessages))
    } yield frame

    def outFrameStream: Pipe[F, Frame, Unit] = (frames: Stream[F, Frame]) =>
      frames
        .through(tracingPipe(Out))
        .through(StreamEncoder.many[Frame](Codec[Frame].asEncoder).toPipeByte)
        .through(socket.writes())

    def inFrameStream: Stream[F, Frame] =
      socket.reads(NUM_BYTES)
        .through(StreamDecoder.many[Frame](Codec[Frame].asDecoder).toPipeByte)
        .through(tracingPipe(In))
  }
}
