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

package net.sigusr.mqtt.api

import cats.effect.std.Console
import cats.effect.{Concurrent, Resource, Temporal}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.net.Network
import net.sigusr.mqtt.api.Errors.ProtocolError
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.impl.frames.Builders._
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.protocol.Result.QoS
import net.sigusr.mqtt.impl.protocol.{IdGenerator, Protocol, Transport}

sealed case class Message(topic: String, payload: Vector[Byte])

trait Session[F[_]] {

  def messages: Stream[F, Message]

  def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]]

  def unsubscribe(topics: Vector[String]): F[Unit]

  def publish(
      topic: String,
      payload: Vector[Byte],
      qos: QualityOfService = AtMostOnce,
      retain: Boolean = false
  ): F[Unit]

  def state: SignallingRef[F, ConnectionState]

}

object Session {

  def apply[F[_]: Temporal: Network: Console](
      transportConfig: TransportConfig[F],
      sessionConfig: SessionConfig
  ): Resource[F, Session[F]] = Resource(fromTransport(transportConfig, sessionConfig))

  private def fromTransport[F[_]: Temporal: Network : Console](
      transportConfig: TransportConfig[F],
      sessionConfig: SessionConfig
  ): F[(Session[F], F[Unit])] =
    for {

      ids <- IdGenerator[F](1)
      protocol <- Protocol(sessionConfig, Transport[F](transportConfig))

    } yield (
      new Session[F] {

        override val messages: Stream[F, Message] = protocol.messages

        override def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]] =
          for {
            messageId <- ids.next
            v <- protocol.sendReceive(subscribeFrame(messageId, topics), messageId)
          } yield v match {
            case QoS(t) => topics.zip(t).map(p => (p._1._1, QualityOfService.fromOrdinal(p._2)))
            case _      => throw ProtocolError
          }

        override def unsubscribe(topics: Vector[String]): F[Unit] =
          for {
            messageId <- ids.next
            _ <- protocol.sendReceive(unsubscribeFrame(messageId, topics), messageId)
          } yield ()

        override def publish(topic: String, payload: Vector[Byte], qos: QualityOfService, retain: Boolean): F[Unit] =
          qos match {
            case QualityOfService.AtMostOnce =>
              protocol.send(publishFrame(topic, None, payload, qos, retain))
            case QualityOfService.AtLeastOnce | QualityOfService.ExactlyOnce =>
              for {
                messageId <- ids.next
                _ <- protocol.sendReceive(publishFrame(topic, Some(messageId), payload, qos, retain), messageId)
              } yield ()
          }

        override val state: SignallingRef[F, ConnectionState] = protocol.state
      },
      disconnect(ids, protocol)
    )

  private def disconnect[F[_]: Concurrent](ids: IdGenerator[F], protocol: Protocol[F]): F[Unit] = {
    val disconnectMessage = DisconnectFrame(Header())
    ids.cancel *> protocol.send(disconnectMessage)
  }
}
