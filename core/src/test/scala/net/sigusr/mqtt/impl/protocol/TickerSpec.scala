package net.sigusr.mqtt.impl.protocol

import cats.effect.IO
import cats.effect.Ref
import cats.effect.testing.specs2.CatsEffect
import cats.effect.testkit.TestControl
import org.specs2.mutable._

import scala.concurrent.duration.DurationInt

class TickerSpec extends Specification with CatsEffect {
  "A ticker" should {

    "Trigger a program when an given time is elapsed" in {
      for {
        ref <- Ref[IO].of(false)
        control <- TestControl.execute(Ticker[IO](30, ref.set(true)))
        _ <- control.advanceAndTick(31.seconds)
        result <- ref.get.map(_ must beTrue)
      } yield result
    }

    "Not trigger a program when an given time is not yet elapsed" in {
      for {
        ref <- Ref[IO].of(false)
        control <- TestControl.execute(Ticker[IO](30, ref.set(true)))
        _ <- control.advanceAndTick(29.seconds)
        result <- ref.get.map(_ must beFalse)
      } yield result
    }

    "Not trigger a program when an given time is elapsed but it has been reset" in {
      for {
        ref <- Ref[IO].of(false)
        control <- TestControl.execute(
          Ticker[IO](30, ref.set(true))
            .flatMap { t =>
              IO.sleep(20.seconds) *> t.reset
            }
        )
        _ <- control.advanceAndTick(30.seconds)
        result <- ref.get.map(_ must beFalse)
      } yield result
    }

    "Be cancellable" in {
      for {
        ref <- Ref[IO].of(false)
        control <- TestControl.execute{
          Ticker[IO](30, ref.set(true))
            .flatMap { t =>
              IO.sleep(30.seconds) *> t.cancel
            }
        }
        _ <- control.advanceAndTick(30.seconds)
        result <- ref.get.map(_ must beFalse)
        control2 <- TestControl.execute{
          Ticker[IO](30, ref.set(true))
            .flatMap { t =>
              IO.sleep(31.seconds) *> t.cancel
            }
        }
        _ <- control2.advanceAndTick(30.seconds)
        result2 <- ref.get.map(_ must beTrue)
      } yield result.and(result2)
    }
  }
}
