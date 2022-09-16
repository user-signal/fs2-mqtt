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

object ToMetrics {
  type Typeclass[T] = ToMetrics[T]

  def join[T](ctx: CaseClass[ToMetrics, T]): ToMetrics[T] = new ToMetrics[T] {
    override def toMetrics(name: String): Seq[Metric] = Seq(
      Metric(
        name = Some(name),
        value = Metric.Value.TemplateValue(
          Template(
            metrics = ctx.parameters
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
        metrics = ctx.parameters
          .flatMap { p =>
            p.typeclass.toMetrics(p.label, p.dereference(t))
          }
      )
    )
  }

  def split[T](ctx: SealedTrait[ToMetrics, T]): ToMetrics[T] =
    new ToMetrics[T] {
      override def toMetrics(name: String): Seq[Metric] = Seq()
      override def toMetrics(name: String, value: T): Seq[Metric] = ctx.split(value) { sub =>
        sub.typeclass.toMetrics(name, sub.cast(value))
      }

      override def dataType: DataType = DataType.Template
      override def wrapValue(t: T): Metric.Value = Metric.Value.Empty
    }

  implicit val booleanGen: ToMetrics[Boolean] = new ToMetrics[Boolean] {
    override val dataType: DataType = DataType.Boolean
    override def wrapValue(t: Boolean): Metric.Value = Metric.Value.BooleanValue(t)
  }

  implicit val intGen: ToMetrics[Int] = new ToMetrics[Int] {
    override val dataType: DataType = DataType.Int32
    override def wrapValue(t: Int): Metric.Value = Metric.Value.IntValue(t)
  }

  implicit val longGen: ToMetrics[Long] = new ToMetrics[Long] {
    override val dataType: DataType = DataType.Int64
    override def wrapValue(t: Long): Metric.Value = Metric.Value.LongValue(t)
  }

  implicit val doubleGen: ToMetrics[Double] = new ToMetrics[Double] {
    override val dataType: DataType = DataType.Double
    override def wrapValue(t: Double): Metric.Value = Metric.Value.DoubleValue(t)
  }

  implicit val floatGen: ToMetrics[Float] = new ToMetrics[Float] {
    override val dataType: DataType = DataType.Float
    override def wrapValue(t: Float): Metric.Value = Metric.Value.FloatValue(t)
  }

  implicit val stringGen: ToMetrics[String] = new ToMetrics[String] {
    override val dataType: DataType = DataType.String
    override def wrapValue(t: String): Metric.Value = Metric.Value.StringValue(t)
  }
  implicit def gen[T]: ToMetrics[T] = macro Magnolia.gen[T]
}
