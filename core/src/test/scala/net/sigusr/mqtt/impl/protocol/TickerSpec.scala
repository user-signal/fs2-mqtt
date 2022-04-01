package net.sigusr.mqtt.impl.protocol

import cats.effect.testing.specs2.CatsEffect
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import org.specs2.mutable._

import scala.concurrent.duration.DurationInt

class TickerSpec extends Specification with CatsEffect {

  def steps[T](control: TestControl[T], count: Int): IO[Unit] = {
    val io = control.nextInterval flatMap control.advanceAndTick
    if (count == 1) io else io.flatMap(_ => steps(control, count - 1))
  }
  
  "A ticker" should {

    "Trigger a program only when a given time is elapsed" in {
      for {
        ref <- Ref[IO].of(false)
        control <- TestControl.execute(Ticker[IO](30, ref.set(true)))
        _ <- control.tick
        _ <- steps(control, 29)
        result0 <- ref.get.map(_ must beFalse)
        _ <- steps(control, 30)
        result1 <- ref.get.map(_ must beTrue)
      } yield result0.and(result1)
    }
    
    "Trigger a program after an extended elapsed time when it has been reset" in {
      for {
        ref <- Ref[IO].of(false)
        control <- TestControl.execute(
          Ticker[IO](30, ref.set(true))
            .flatMap { t =>
              IO.sleep(20.seconds) *> t.reset
            }
        )
        _ <- control.tick
        _ <- steps(control, 30)
        result <- ref.get.map(_ must beFalse)
        _ <- steps(control, 20)
        result2 <- ref.get.map(_ must beTrue)
      } yield result.and(result2)
    }

    "Not Trigger a program when canceled before a given time is elapsed" in {
      for {
        ref <- Ref[IO].of(false)
        control <- TestControl.execute {
          // This is needed to guaranty there is an available task to 
          // schedule even when the second task is canceled
          IO.sleep(30.seconds).racePair( 
          Ticker[IO](30, ref.set(true))
            .flatMap { t =>
              IO.sleep(29.seconds) *> t.cancel
            })
        }
        _ <- control.tick
        _ <- steps(control, 30)
        result <- ref.get.map(_ must beFalse)
      } yield result
    }
  }
}
