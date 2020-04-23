package net.sigusr.mqtt.impl.net

import net.sigusr.mqtt.api.ConnectionFailureReason

import scala.util.control.NoStackTrace

trait Errors extends NoStackTrace

object Errors {
  case object InternalError extends Errors
  case class DecodingError(message: String) extends Errors
  case class ConnectionFailure(reason: ConnectionFailureReason) extends Errors
}
