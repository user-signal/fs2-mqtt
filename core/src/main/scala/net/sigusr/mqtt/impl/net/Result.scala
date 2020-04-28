package net.sigusr.mqtt.impl.net

sealed trait Result
object Result {
  case object Empty extends Result
  case class QoS(values: Vector[Int]) extends Result
}

