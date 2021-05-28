package net.sigusr.impl.protocol

import enumeratum.values.{CharEnum, CharEnumEntry}

import scala.collection.immutable

sealed abstract class Direction(val value: Char, val color: String, val active: Boolean) extends CharEnumEntry
object Direction extends CharEnum[Direction] {
  case class In(override val active: Boolean) extends Direction('←', Console.YELLOW, active)
  case class Out(override val active: Boolean) extends Direction('→', Console.GREEN, active)

  val values: immutable.IndexedSeq[Direction] = findValues
}

