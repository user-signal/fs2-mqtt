package net.sigusr.mqtt.sparkplug

import magnolia1._
import org.eclipse.tahu.protobuf.sparkplug_b.DataType
import org.eclipse.tahu.protobuf.sparkplug_b.Payload.{Metric, Template}


trait ToMetrics[T] {
  def toMetrics(name: String): Seq[Metric] = Seq(
    Metric(name = Some(name), datatype = Some(dataType.value), value = Metric.Value.Empty, isNull = Some(true))
  )
  def toMetrics(name: String, value: T): Seq[Metric] = Seq(
    Metric(name = Some(name), datatype = Some(dataType.value), value = wrapValue(value))
  )
  def dataType: DataType
  def wrapValue(t: T): Metric.Value
}

object ToMetrics extends AutoDerivation[ToMetrics] {
  type Typeclass[T] = ToMetrics[T]

  def join[T](ctx: CaseClass[ToMetrics, T]): ToMetrics[T] = new ToMetrics[T] {
    override def toMetrics(name: String): Seq[Metric] = Seq(
      Metric(
        name = Some(name),
        value = Metric.Value.TemplateValue(
          Template(
            metrics = ctx.params
              .flatMap { p =>
                p.typeclass.toMetrics(p.label)
              },
            isDefinition = Some(true)
          )
        )
      )
    )

    override def dataType: DataType = DataType.Template
    override def wrapValue(t: T): Metric.Value = Metric.Value.TemplateValue(
      Template(
        metrics = ctx.params
          .flatMap { p =>
            p.typeclass.toMetrics(p.label, p.deref(t))
          }
      )
    )
  }

  def split[T](ctx: SealedTrait[ToMetrics, T]): ToMetrics[T] =
    new ToMetrics[T] {
      override def toMetrics(name: String): Seq[Metric] = Seq()
      override def toMetrics(name: String, value: T): Seq[Metric] = ctx.choose(value) { sub =>
        sub.typeclass.toMetrics(name, sub.cast(value))
      }

      override def dataType: DataType = DataType.Template
      override def wrapValue(t: T): Metric.Value = Metric.Value.Empty
    }

  given ToMetrics[Boolean] = new ToMetrics[Boolean] {
    override val dataType: DataType = DataType.Boolean
    override def wrapValue(t: Boolean): Metric.Value = Metric.Value.BooleanValue(t)
  }

  given ToMetrics[Int] = new ToMetrics[Int] {
    override val dataType: DataType = DataType.Int32
    override def wrapValue(t: Int): Metric.Value = Metric.Value.IntValue(t)
  }

  given ToMetrics[Long] = new ToMetrics[Long] {
    override val dataType: DataType = DataType.Int64
    override def wrapValue(t: Long): Metric.Value = Metric.Value.LongValue(t)
  }

  given ToMetrics[Double] = new ToMetrics[Double] {
    override val dataType: DataType = DataType.Double
    override def wrapValue(t: Double): Metric.Value = Metric.Value.DoubleValue(t)
  }

  given ToMetrics[Float] = new ToMetrics[Float] {
    override val dataType: DataType = DataType.Float
    override def wrapValue(t: Float): Metric.Value = Metric.Value.FloatValue(t)
  }

  given ToMetrics[String] = new ToMetrics[String] {
    override val dataType: DataType = DataType.String
    override def wrapValue(t: String): Metric.Value = Metric.Value.StringValue(t)
  }

  inline def gen[T: deriving.Mirror.Of] = ToMetrics.derived[T]
}
