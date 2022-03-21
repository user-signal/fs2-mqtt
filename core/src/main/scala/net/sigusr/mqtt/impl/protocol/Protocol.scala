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

import cats.effect.{Concurrent, Deferred, Ref, Temporal}
import cats.effect.std.Queue
import cats.implicits._
import fs2.concurrent.SignallingRef
import fs2.{INothing, Pipe, Pull, Stream}
import net.sigusr.mqtt.api.ConnectionState.{Connected, Disconnected, Error, SessionStarted}
import net.sigusr.mqtt.api.Errors.{ConnectionFailure, ProtocolError}
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.api._
import net.sigusr.mqtt.impl.frames.Builders.connectFrame
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.protocol.Result.{Empty, QoS}
import scodec.bits.ByteVector

trait Protocol[F[_]] {

  def send: Frame => F[Unit]

  def sendReceive(frame: Frame, messageId: Int): F[Result]

  def cancel: F[Unit]

  def messages: Stream[F, Message]

  def state: SignallingRef[F, ConnectionState]

}

object Protocol {

  def apply[F[_]: Temporal](
      sessionConfig: SessionConfig,
      transport: TransportConnector[F] => F[Transport[F]]
  ): F[Protocol[F]] = {

    def inboundMessagesInterpreter(
        messageQueue: Queue[F, Message],
        frameQueue: Queue[F, Frame],
        inFlightOutBound: AtomicMap[F, Int, Frame],
        pendingResults: AtomicMap[F, Int, Deferred[F, Result]],
        stateSignal: SignallingRef[F, ConnectionState],
        closeSignal: SignallingRef[F, Boolean],
        pingAcknowledged: Ref[F, Boolean]
    ): Pipe[F, Frame, Unit] = {

      def loop(s: Stream[F, Frame], inFlightInBound: Set[Int]): Pull[F, INothing, Unit] =
        s.pull.uncons1.flatMap {
          case Some((hd, tl)) =>
            hd match {

              case PublishFrame(header: Header, topic: String, messageIdentifier: Option[Int], payload: ByteVector) =>
                (header.qos, messageIdentifier) match {
                  case (AtMostOnce.value, None) =>
                    Pull.eval(messageQueue.offer(Message(topic, payload.toArray.toVector))) >>
                      loop(tl, inFlightInBound)
                  case (AtLeastOnce.value, Some(id)) =>
                    Pull.eval(
                      messageQueue.offer(Message(topic, payload.toArray.toVector)) >> frameQueue
                        .offer(PubackFrame(Header(), id))
                    ) >>
                      loop(tl, inFlightInBound)
                  case (ExactlyOnce.value, Some(id)) =>
                    Pull.eval(
                      if (inFlightInBound.contains(id))
                        frameQueue.offer(PubrecFrame(Header(), id))
                      else
                        messageQueue.offer(Message(topic, payload.toArray.toVector)) >> frameQueue
                          .offer(PubrecFrame(Header(), id))
                    ) >>
                      loop(tl, inFlightInBound + id)
                  case (_, _) => Pull.eval(stateSignal.set(Error(ProtocolError)) >> closeSignal.set(true))
                }

              case PubackFrame(_: Header, messageIdentifier) =>
                Pull.eval(
                  inFlightOutBound.remove(messageIdentifier) >> pendingResults.remove(messageIdentifier) >>=
                    (_.fold(Concurrent[F].unit)(_.complete(Empty).void))
                ) >>
                  loop(tl, inFlightInBound)

              case PubrelFrame(header, messageIdentifier) =>
                Pull.eval(frameQueue.offer(PubcompFrame(header.copy(qos = 0), messageIdentifier))) >>
                  loop(tl, inFlightInBound - messageIdentifier)

              case PubcompFrame(_, messageIdentifier) =>
                Pull.eval(
                  inFlightOutBound.remove(messageIdentifier) >> pendingResults.remove(messageIdentifier) >>=
                    (_.fold(Concurrent[F].unit)(_.complete(Empty).void))
                ) >>
                  loop(tl, inFlightInBound)

              case PubrecFrame(header, messageIdentifier) =>
                val pubrelFrame = PubrelFrame(header, messageIdentifier)
                Pull.eval(
                  inFlightOutBound.update(messageIdentifier, pubrelFrame) >> frameQueue.offer(pubrelFrame)
                ) >>
                  loop(tl, inFlightInBound)

              case PingRespFrame(_: Header) =>
                Pull.eval(pingAcknowledged.set(true)) >> loop(tl, inFlightInBound)

              case UnsubackFrame(_: Header, messageIdentifier) =>
                Pull.eval(
                  inFlightOutBound.remove(messageIdentifier) >> pendingResults.remove(messageIdentifier) >>=
                    (_.fold(Concurrent[F].unit)(_.complete(Empty).void))
                ) >>
                  loop(tl, inFlightInBound)

              case SubackFrame(_: Header, messageIdentifier, topics) =>
                Pull.eval(
                  inFlightOutBound.remove(messageIdentifier) >> pendingResults.remove(messageIdentifier) >>=
                    (_.fold(Concurrent[F].unit)(_.complete(QoS(topics)).void))
                ) >>
                  loop(tl, inFlightInBound)

              case ConnackFrame(_: Header, returnCode) =>
                if (returnCode == 0)
                  Pull.eval(stateSignal.set(SessionStarted)) >>
                    loop(tl, inFlightInBound)
                else
                  Pull.eval(
                    stateSignal.set(
                      Error(ConnectionFailure(ConnectionFailureReason.fromOrdinal(returnCode)))
                    ) >> closeSignal.set(true)
                  )

              case _ =>
                Pull.eval(stateSignal.set(Error(ProtocolError)) >> closeSignal.set(true))
            }

          case None => Pull.done
        }
      loop(_, Set.empty[Int]).stream
    }

    def outboundMessagesInterpreter(
        inFlightOutBound: AtomicMap[F, Int, Frame],
        stateSignal: SignallingRef[F, ConnectionState],
        pingTicker: Ticker[F]
    ): Pipe[F, Frame, Frame] =
      in =>
        stateSignal.discrete.flatMap {
          case SessionStarted =>
            in.flatMap { hd =>
              (hd match {
                case PublishFrame(_: Header, _, messageIdentifier, _) =>
                  Stream.eval(messageIdentifier.fold(Concurrent[F].pure[Unit](()))(inFlightOutBound.update(_, hd)))
                case SubscribeFrame(_: Header, messageIdentifier, _) =>
                  Stream.eval(inFlightOutBound.update(messageIdentifier, hd))
                case UnsubscribeFrame(_: Header, messageIdentifier, _) =>
                  Stream.eval(inFlightOutBound.update(messageIdentifier, hd))
                case _ => Stream.eval(Concurrent[F].pure[Unit](()))
              }) >> Stream.eval(pingTicker.reset) >> Stream.emit(Some(hd))
            }
          case Connected =>
            Stream.emit(Some(connectFrame(sessionConfig))) ++
              (if (sessionConfig.cleanSession)
                 Stream.emit(None)
               else
                 Stream
                   .eval(
                     inFlightOutBound.removeAll().map(m => m.map(setDupFlag).map(Some(_))).map(x => Stream.emits(x))
                   )
                   .flatten)
          case _ => Stream.emit(None)
        }.unNone

    def pingOrDisconnect(
        pingAcknowledged: Ref[F, Boolean],
        frameQueue: Queue[F, Frame],
        closeSignal: SignallingRef[F, Boolean]
    ): F[Unit] =
      pingAcknowledged.get >>= (if (_) pingAcknowledged.set(false) >> frameQueue.offer(PingReqFrame(Header()))
                                else pingAcknowledged.set(true) >> closeSignal.set(true))

    for {

      messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
      frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
      stopSignal <- SignallingRef[F, Boolean](false)
      inFlightOutBound <- AtomicMap[F, Int, Frame]
      pendingResults <- AtomicMap[F, Int, Deferred[F, Result]]
      stateSignal <- SignallingRef[F, ConnectionState](Disconnected)
      closeSignal <- SignallingRef[F, Boolean](false)
      pingAcknowledged <- Ref.of[F, Boolean](true)
      pingTicker <- Ticker(sessionConfig.keepAlive.toLong, pingOrDisconnect(pingAcknowledged, frameQueue, closeSignal))

      inbound: Pipe[F, Frame, Unit] = inboundMessagesInterpreter(
        messageQueue,
        frameQueue,
        inFlightOutBound,
        pendingResults,
        stateSignal,
        closeSignal,
        pingAcknowledged
      )

      outbound: Stream[F, Frame] =
        Stream.fromQueueUnterminated(frameQueue).through(outboundMessagesInterpreter(inFlightOutBound, stateSignal, pingTicker))

      _ <- transport(TransportConnector[F](inbound, outbound, stateSignal, closeSignal))

    } yield new Protocol[F] {

      override def cancel: F[Unit] = stopSignal.set(true) *> pingTicker.cancel

      override def send: Frame => F[Unit] = frameQueue.offer

      override def sendReceive(frame: Frame, messageId: Int): F[Result] =
        for {
          d <- Deferred[F, Result]
          _ <- pendingResults.update(messageId, d)
          _ <- frameQueue.offer(frame)
          r <- d.get
        } yield r

      override val messages: Stream[F, Message] = Stream.fromQueueUnterminated(messageQueue).interruptWhen(stopSignal)

      override val state: SignallingRef[F, ConnectionState] = stateSignal
    }
  }
}
