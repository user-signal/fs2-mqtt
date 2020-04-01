package net.sigusr

import cats.MonadError

package object mqtt {
  type MonadThrow[F[_]] = MonadError[F, Throwable]
}
