# A pure functional Scala MQTT client library [![Build Status](https://travis-ci.org/user-signal/fs2-mqtt.svg?branch=master)](https://travis-ci.org/user-signal/fs2-mqtt) [![Coverage Status](https://coveralls.io/repos/user-signal/fs2-mqtt/badge.png?branch=master)](https://coveralls.io/r/user-signal/fs2-mqtt?branch=master)

## Back then...

Late 2014, I initiated an [MQTT client library for Scala](https://github.com/fcabestre/Scala-MQTT-client) side project. 
My purpose was to learn me some [Akka](http://akka.io) while trying to deliver something potentially useful. I quickly 
found the excellent [Scodec](http://typelevel.org/projects/scodec) library to encode/decode *MQTT* protocol frames, making
this part of the work considerably easier, with a concise and very readable outcome.

More recently, while getting more and more interest in pure functional programming in *Scala*, in had the chance to see
this amazing talk on [Skunk](https://youtu.be/NJrgj1vQeAI) from @tpolecat. It's about building, from the ground up, a 
data access library for *Postgres* based on [FS2](https://fs2.io) and… *Scodec*.

## Oops!… I did it again.

I rushed to [Skunk](https://github.com/tpolecat/skunk), which as been inspirational, and took the opportunity of these 
lock down days to learn a lot about [cats](https://typelevel.org/cats/), [cats effects](https://github.com/typelevel/cats-effect) 
and of course *FS2*. I even found the integration between *FS2* and *Scodec*, [Scodec-stream](https://github.com/scodec/scodec-stream), 
to be utterly useful.

With all these tools at hand, and the book [Practical FP in Scala: A hands-on approach](https://leanpub.com/pfp-scala)
on my desk, it has been quite (sic) easy to connect everything together and build this purely functional Scala MQTT
client library.

## Current status

I have a basic and far from complete implementation of the thing. Frame encoding and decoding works pretty well, and
it's possible to write some code to talk to [Mosquitto](http://mosquitto.org). 

For examples you can have a look to the [local subscriber](https://github.com/user-signal/fs2-mqtt/blob/master/examples/src/main/scala/net/sigusr/mqtt/examples/LocalSubscriber.scala) or the
[local publisher](https://github.com/user-signal/fs2-mqtt/blob/master/examples/src/main/scala/net/sigusr/mqtt/examples/LocalPublisher.scala).

## Releases

[ci]: https://travis-ci.org/user-signal/fs2-mqtt/
[sonatype]: https://oss.sonatype.org/index.html#nexus-search;quick~scala-mqtt-client

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
    "net.sigusr" %% "fs2-mqtt" % "0.1.0"
)
```

There is a cross built set up for Scala 2.11, 2.12 and 2.13.

## Dependencies

Roughly speaking this library depends on:
 * [Scala](https://www.scala-lang.org/) of course, but not [Scala.js](https://www.scala-js.org/) even thought this should be fairly easy…
 * [FS2](https://fs2.io) 
 * [Scodec](http://typelevel.org/projects/scodec) and [Scodec-stream](https://github.com/scodec/scodec-stream)
 * [Cats effects](https://github.com/typelevel/cats-effect) for some internal concurrency stuff
 
I have no checked yet, but client code using this library should work seamlessly whether it uses [Monix](https://monix.io/) 
or [ZIO](https://zio.dev/) as both support *cats effects* typeclasses.

## License

This work is licenced under an [Apache Version 2.0 license](http://github.com/user-signal/fs2-mqtt/blob/master/LICENSE)
