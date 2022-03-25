package net.sigusr.mqtt.impl.protocol

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.syntax.all._
import org.specs2.mutable._

// http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718025
class IdGeneratorSpec extends Specification with CatsEffect {

  val context = new net.sigusr.mqtt.SpecUtils.CatsContext
  import context._

  "An id generator" should {

    "Provide ids > 0" in {
      IdGenerator[IO]().flatMap(gen =>
        gen.next.replicateA(100*1000)
          .map(ids => ids.forall(_ > 0) must beTrue)
      )
    }

    "Provide ids <= 65535" in {
      IdGenerator[IO]().flatMap(gen =>
        gen.next.replicateA(100*1000)
          .map(ids => ids.forall(_ <= 65535) must beTrue)
      )
    }

    "Provide 65535 different ids" in {
      IdGenerator[IO]().flatMap(gen =>
        gen.next.replicateA(65535)
          .map(ids => ids.distinct.size must_== ids.size)
      )
    }
  }
}
