package net.sigusr.mqtt.impl.protocol

import fs2.concurrent.SignallingRef
import fs2.{Pipe, Stream}
import net.sigusr.mqtt.api.ConnectionState
import net.sigusr.mqtt.impl.frames.Frame

case class TransportConnector[F[_]](
    in: Pipe[F, Frame, Unit],
    out: Stream[F, Frame],
    stateSignal: SignallingRef[F, ConnectionState],
    closeSignal: SignallingRef[F, Boolean]
)
