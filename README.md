# A pure functional Scala MQTT client library [![Build Status](https://travis-ci.org/user-signal/fs2-mqtt.svg?branch=master)](https://travis-ci.org/user-signal/fs2-mqtt) [![Coverage Status](https://coveralls.io/repos/user-signal/fs2-mqtt/badge.png?branch=master)](https://coveralls.io/r/user-signal/fs2-mqtt?branch=master)

## Back then...

Late 2014, I initiated an [MQTT client library for Scala](https://github.com/fcabestre/Scala-MQTT-client) side project. 
My purpose was to learn me some [Akka](http://akka.io) while trying to deliver something potentially useful. I quickly 
found the excellent [Scodec](http://typelevel.org/projects/scodec) library to encode/decode *MQTT* protocol frames, making
this part of the work considerably easier, with a concise and very readable outcome.

More recently, while getting more and more interest in pure functional programming in *Scala*, in had the chance to see
this amazing talk on [Skunk](https://youtu.be/NJrgj1vQeAI) from [@tpolecat](https://twitter.com/tpolecat). It's about 
building, from the ground up, a data access library for *Postgres* based on [FS2](https://fs2.io) and… *Scodec*.

## Oops!… I did it again.

I rushed to [Skunk](https://github.com/tpolecat/skunk), which as been inspirational, and took the opportunity of these 
lock down days to learn a lot about [cats](https://typelevel.org/cats/), [cats effects](https://github.com/typelevel/cats-effect) 
and of course *FS2*. I even found the integration between *FS2* and *Scodec*, [Scodec-stream](https://github.com/scodec/scodec-stream), 
to be utterly useful.

With all these tools at hand, and the book [Practical FP in Scala: A hands-on approach](https://leanpub.com/pfp-scala)
on my desk, it has been quite (sic) easy to connect everything together and build this purely functional Scala MQTT
client library.

## Current status

This library is build in the *tagless final* style in order to make it, as much as possible, *IO monad* agnostic for the
client code using it. It's internals are nevertheless mainly build around *FS2*, *cats effetcs* typeclasses and concurrency 
primitives.  

It implements almost all the *MQTT* `3.1.1` protocol and allows interacting with a [Mosquitto](http://mosquitto.org) 
broker. It does not support *MQTT* `5` and, to tell the truth, this is not even envisioned! Still, there's work ahead:
 * even if it includes the required message exchanges for QoS 1 and 2, there is no replay of in flight messages,
 * the connection/session management needs a lot of (re)design,
 * cross builds would be nice… even considering *Scala.js*,
 * …

[local subscriber]: https://github.com/user-signal/fs2-mqtt/blob/master/examples/src/main/scala/net/sigusr/mqtt/examples/LocalSubscriber.scala
[local publisher]: https://github.com/user-signal/fs2-mqtt/blob/master/examples/src/main/scala/net/sigusr/mqtt/examples/LocalPublisher.scala

For examples on how to use it, you can have a look at the [local subscriber][local subscriber] or the [local publisher][local publisher] 
code. The former is build using [ZIO](https://zio.dev/) while the later is based on [Monix](https://monix.io/).

## Releases

[ci]: https://travis-ci.org/user-signal/fs2-mqtt/
[sonatype]: https://oss.sonatype.org/index.html#nexus-search;quick~fs2-mqtt

Artifacts are available at [Sonatype OSS Repository Hosting service][sonatype], even the ```SNAPSHOTS``` automatically
built by [Travis CI][ci]. To include the Sonatype repositories in your SBT build you should add,

```scala
resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots")
)
```

In case you want to easily give a try to this library, without the burden of adding resolvers, there is a release synced
to Maven Central. In this case just add,

```scala
scalaVersion := "2.13.2"

libraryDependencies ++= Seq(
    "net.sigusr" %% "fs2-mqtt" % "0.2.0"
)
```

## Dependencies

Roughly speaking this library depends on:
 * [Scala](https://www.scala-lang.org/) of course, but not [Scala.js](https://www.scala-js.org/) even thought this should be fairly easy…
 * [FS2](https://fs2.io) 
 * [Scodec](http://typelevel.org/projects/scodec) and [Scodec-stream](https://github.com/scodec/scodec-stream)
 * [Cats effects](https://github.com/typelevel/cats-effect) for some internal concurrency stuff
 
This library should work seamlessly with various compatible IO monads: [cats effects IO](https://typelevel.org/cats-effect/datatypes/io.html) 
of course, but [Monix](https://monix.io/) and [ZIO](https://zio.dev/) as well as both support *cats effects* typeclasses.

## License

This work is licenced under an [Apache Version 2.0 license](http://github.com/user-signal/fs2-mqtt/blob/master/LICENSE)
