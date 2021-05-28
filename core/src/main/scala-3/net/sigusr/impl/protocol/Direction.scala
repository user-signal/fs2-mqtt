package net.sigusr.impl.protocol

enum Direction(val value: Char, val color: String, val active: Boolean):
  case In(override val active: Boolean) extends Direction('←', Console.YELLOW, active)
  case Out(override val active: Boolean) extends Direction('→', Console.GREEN, active)

