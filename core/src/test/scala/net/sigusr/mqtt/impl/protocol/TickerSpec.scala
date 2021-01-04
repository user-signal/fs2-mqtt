package net.sigusr.mqtt.impl.protocol

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable._

import scala.concurrent.duration.DurationInt

class TickerSpec extends Specification with CatsEffect{
  "A ticker" should {

    "Trigger a program when an given time is elapsed" in {
      val context = new net.sigusr.mqtt.SpecUtils.CatsContext
      import context._
      Ref[IO].of(false).flatMap { ref =>
        Ticker[IO](30, ref.set(true)).unsafeRunSync()
        ec.tick(31.seconds)
        ref.get.map(_ must beTrue)
      }
    }

    "Not trigger a program when an given time is not yet elapsed" in {
      val context = new net.sigusr.mqtt.SpecUtils.CatsContext
      import context._
      Ref[IO].of(false).flatMap { ref =>
        Ticker[IO](30, ref.set(true)).unsafeRunSync()
        ec.tick(29.seconds)
        ref.get.map(_ must beFalse)
      }
    }

    "Not trigger a program when an given time is elapsed but it has been reset" in {
      val context = new net.sigusr.mqtt.SpecUtils.CatsContext
      import context._
      Ref[IO].of(false).flatMap { ref =>
        Ticker[IO](30, ref.set(true)).flatMap { t =>
          ec.tick(29.seconds)
          t.reset
          ec.tick(2.seconds)
          ref.get.map(_ must beFalse)
        }
      }
    }
  }
}