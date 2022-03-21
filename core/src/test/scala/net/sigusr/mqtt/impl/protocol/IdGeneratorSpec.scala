package net.sigusr.mqtt.impl.protocol

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable._

class IdGeneratorSpec extends Specification with CatsEffect {

  "An id generator" should {

    "Provide the next id" in {
      IdGenerator[IO](41).flatMap { g =>
        for {
          _ <- g.next
          n <- g.next
        } yield n must_== 42
      }
    }

    "Provide ids modulus 65535" in {
      IdGenerator[IO](65534).flatMap { g =>
        for {
          _ <- g.next
          n <- g.next
        } yield n must_== 1
      }
    }
  }
}
