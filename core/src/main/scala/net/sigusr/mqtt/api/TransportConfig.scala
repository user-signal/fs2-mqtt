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

import cats.Applicative
import cats.effect.{Blocker, ContextShift, Sync}
import fs2.io.tls.{TLSContext, TLSParameters}
import net.sigusr.mqtt.api.PredefinedRetryPolicy.{ConstantDelay, ExponentialBackoff, FibonacciBackoff, FullJitter}
import net.sigusr.mqtt.api.RetryConfig.Predefined
import retry.{RetryPolicies, RetryPolicy}

import scala.concurrent.duration._

sealed trait PredefinedRetryPolicy
object PredefinedRetryPolicy {
  case object ConstantDelay extends PredefinedRetryPolicy
  case object ExponentialBackoff extends PredefinedRetryPolicy
  case object FibonacciBackoff extends PredefinedRetryPolicy
  case object FullJitter extends PredefinedRetryPolicy
}

sealed trait RetryConfig[F[_]]
object RetryConfig {
  def policyOf[F[_]: Applicative](retryConfig: RetryConfig[F]): RetryPolicy[F] =
    retryConfig match {
      case Predefined(policy, maxRetries, baseDelay) =>
        RetryPolicies
          .limitRetries(maxRetries)
          .join(basePolicy(policy, baseDelay))
      case Custom(policy) => policy
    }

  private def basePolicy[F[_]: Applicative](
      predefinedRetryPolicy: PredefinedRetryPolicy,
      baseDelay: FiniteDuration
  ): RetryPolicy[F] =
    predefinedRetryPolicy match {
      case ConstantDelay      => RetryPolicies.constantDelay(baseDelay)
      case ExponentialBackoff => RetryPolicies.exponentialBackoff(baseDelay)
      case FibonacciBackoff   => RetryPolicies.fibonacciBackoff(baseDelay)
      case FullJitter         => RetryPolicies.fullJitter(baseDelay)
    }

  case class Predefined[F[_]](
      policy: PredefinedRetryPolicy = FibonacciBackoff,
      maxRetries: Int = 5,
      baseDelay: FiniteDuration = 2.seconds
  ) extends RetryConfig[F]

  case class Custom[F[_]](policy: RetryPolicy[F]) extends RetryConfig[F]
}

sealed trait TLSContextKind
object TLSContextKind {
  case object System extends TLSContextKind
  case object Insecure extends TLSContextKind
}

sealed case class TLSConfig[F[_]: Sync: ContextShift](
    private val tlsContextKind: TLSContextKind,
    tlsParameters: TLSParameters
) {
  def contextOf(blocker: Blocker): F[TLSContext] =
    tlsContextKind match {
      case TLSContextKind.System   => TLSContext.system[F](blocker)
      case TLSContextKind.Insecure => TLSContext.insecure[F](blocker)
    }
}

object TLSConfig {
  def apply[F[_]: Sync: ContextShift](tlsContextKind: TLSContextKind, tlsParameters: TLSParameters = TLSParameters()) =
    new TLSConfig[F](tlsContextKind, tlsParameters)
}

sealed case class TransportConfig[F[_]: Applicative](
    host: String,
    port: Int,
    tlsConfig: Option[TLSConfig[F]] = None,
    readTimeout: Option[FiniteDuration] = None,
    writeTimeout: Option[FiniteDuration] = None,
    retryConfig: RetryConfig[F] = Predefined[F](),
    numReadBytes: Int = 4096,
    traceMessages: Boolean = false
)
