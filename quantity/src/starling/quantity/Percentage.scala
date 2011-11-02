package starling.quantity

import starling.utils.MathUtil
import collection.IterableLike
import math.abs

import starling.utils.ImplicitConversions._
import starling.utils.Pattern._


/**
 * value is the decimal value of a percentage: e.g. 10% = 0.1
 */
case class Percentage(value : Double) {
  import Percentage._
  assert(!value.isInfinite, "Percentage is infinite")
  assert(!value.isNaN, "Percentage is NaN")

  // we need to round to 8 dp because multiplying by 100 can introduce floating point errors
  // e.g. 0.2435 * 100. = 24.349999999999998
  def toLongString:String = MathUtil.roundToNdp(value * 100, 8) + "%"
  def toShortString: String = toShortString(DefaultFormat)
  def toShortString(format: String, addSpace:Boolean = false): String = (value*100).format(format, addSpace = addSpace) + "%"

  override def toString = toLongString

  override def equals(other: Any) = other match {
    case p : Percentage => abs(p.value * 100 - this.value * 100) <= MathUtil.EPSILON
    case _ => false
  }

  override def hashCode = (value * 1000).toInt

  def isZero = value==0
  def * (x : Double) = Percentage(value * x)
  def / (x : Double) = Percentage(value / x)
  def - (rhs : Percentage) = Percentage(value - rhs.value)
  def + (rhs : Percentage) = Percentage(value + rhs.value)
  def unary_- : Percentage = Percentage(-value)

  def decimalValue = value
  def percentageValue = value * 100

  /**
   * If `this` is 0.0 then returns EPSILON, otherwise returns `this`
   */
  def nonZero = this.value match {
    case 0.0 => Percentage.EPSILON
    case _ => this
  }

  def isAlmostZero : Boolean = value.abs < MathUtil.EPSILON

  def toQuantity = new Quantity(percentageValue, UOM.PERCENT)
}

object Percentage {
  implicit def convertToDouble(p : Percentage) : Double = p.decimalValue
  def sum(values : Iterable[Percentage]) = (Percentage(0) /: values)(_+_)
  def average(values : Iterable[Percentage]) = values match {
    case Nil => throw new Exception("Can't get average of empty sequence")
    case _ => Percentage(sum(values).value / values.size)
  }
  val EPSILON = Percentage(MathUtil.EPSILON)
  def apply(q : Quantity) : Percentage = q.toPercentage
  val Regex = """(.*) ?%""".r

  val Parse: Extractor[String, Percentage] = Extractor.from[String](text => text partialMatch {
    case Regex(value) => Percentage(value.toDouble)
  })

  val DefaultFormat = "#0.00"

  /**
   * Where value is 100.0 for 100%
   */
  def fromPercentage(value: Double) = new Percentage(value / 100.0)

  /**
   * Where value is 1.0 for 100%
   */
  def fromDecimal(value: Double) = new Percentage(value)
}

