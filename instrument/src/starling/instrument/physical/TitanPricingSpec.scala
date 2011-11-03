package starling.instrument.physical

import starling.curves.Environment
import starling.market.{FuturesMarket, CommodityMarket, FuturesExchangeFactory, IndexWithDailyPrices}
import starling.daterange._
import starling.quantity.{Ratio, UOM, Quantity}
import starling.quantity.UOM._

trait TitanPricingSpec {

  def price(env: Environment): Quantity
  def settlementDay(marketDay: DayAndTime): Day

  def premiumCCY: Option[UOM] = {
    premium.uom.numeratorUOM match {
      case UOM.NULL => None
      case ccy => Some(ccy)
    }
  }

  def valuationCCY: UOM
  assert(valuationCCY != null, "Valuation currency is null")

  def inValuationCurrency(env : Environment, p : Quantity) = {
    val namedEnv = env.withNaming()
    val baseCurrency = p.numeratorUOM.inBaseCurrency
    val priceInBaseCurrency = p inUOM (baseCurrency / p.denominatorUOM)
    val fxRate = namedEnv.forwardFXRate(valuationCCY, priceInBaseCurrency.numeratorUOM, settlementDay(namedEnv.marketDay)) 

    priceInBaseCurrency * fxRate
  }
  def addPremiumConvertingIfNecessary(env: Environment, price: Quantity, premium: Quantity): Quantity = {
    val priceInValuationCurrency = inValuationCurrency(env, price)

    if (premium == Quantity.NULL) // no premium case
      priceInValuationCurrency 
    else 
      priceInValuationCurrency + inValuationCurrency(env, premium.named("Premium"))
  }

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity): Quantity

  def isComplete(marketDay: DayAndTime): Boolean

  def pricingType: String

  def daysForPositionReport(marketDay: DayAndTime): List[Day]

  def quotationPeriod: Option[DateRange]

  def premium: Quantity

  def indexOption: Option[IndexWithDailyPrices]

  def expiryDay: Day
}

object TitanPricingSpec {
  def calcSettlementDay(index: IndexWithDailyPrices, day: Day) = {
    val lme = FuturesExchangeFactory.LME
    val market: CommodityMarket = index.market
    market.asInstanceOf[FuturesMarket].exchange match {
      case `lme` => day.addBusinessDays(market.businessCalendar, 2)
      case _ => day
    }
  }

  /**
   * Used for unknown pricing spec and also benchmarks
   * Nothing in common really and its use for the latter is probably wrong
   */
  def representativeDay(index :IndexWithDailyPrices, month : Month, marketDay : DayAndTime) : Day = {
    val lme = FuturesExchangeFactory.LME
    index.market.asInstanceOf[FuturesMarket].exchange match {
      case `lme` => {
        val thirdWednesday = month.firstDay.dayOnOrAfter(DayOfWeek.wednesday) + 14
        if (marketDay >= thirdWednesday.endOfDay)
          month.lastDay.thisOrPreviousBusinessDay(index.businessCalendar)
        else
          thirdWednesday
      }
      case _ => month.lastDay.thisOrPreviousBusinessDay(index.market.businessCalendar)
    }
  }
}

case class AveragePricingSpec(index: IndexWithDailyPrices, period: DateRange,
                              premium: Quantity, valuationCCY : UOM) extends TitanPricingSpec {
  val observationDays = index.observationDays(period)
  // Just a guess
  def settlementDay(marketDay: DayAndTime) = TitanPricingSpec.calcSettlementDay(index, period.lastDay)

  def price(env: Environment) = addPremiumConvertingIfNecessary(env, env.averagePrice(index, period), premium)

  def fixedQuantity(marketDay: DayAndTime,
                    totalQuantity: Quantity): Quantity = totalQuantity * observedDays(marketDay).size / observationDays.size

  def pricingType: String = "Month Average"

  protected def observedDays(marketDay: DayAndTime) = observationDays.filter(_.endOfDay <= marketDay)

  def isComplete(marketDay: DayAndTime) = observedDays(marketDay).size == observationDays.size

  def indexOption = Some(index)

  def quotationPeriod = Some(period)

  def daysForPositionReport(marketDay: DayAndTime) = observationDays.filter(_.endOfDay > marketDay)


  def expiryDay = TitanPricingSpec.calcSettlementDay(index, period.lastDay)
}

case class OptionalPricingSpec(choices: List[TitanPricingSpec], declarationDay: Day,
                               chosenSpec: Option[TitanPricingSpec], valuationCCY : UOM) extends TitanPricingSpec {

  private val specToUse = chosenSpec.getOrElse(choices.head)

  def settlementDay(marketDay: DayAndTime) = specToUse.settlementDay(marketDay)

  def price(env: Environment) = {
    assert(chosenSpec.isDefined || env.marketDay < declarationDay.endOfDay, "Optional pricing spec must be fixed by " + declarationDay)
    specToUse.price(env)
  }

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = specToUse.fixedQuantity(marketDay, totalQuantity)

  def isComplete(marketDay: DayAndTime) = specToUse.isComplete(marketDay)

  def pricingType: String = chosenSpec match {
    case Some(spec) => spec.pricingType;
    case None => "Optional"
  }

  def premium = specToUse.premium

  def daysForPositionReport(marketDay: DayAndTime) = specToUse.daysForPositionReport(marketDay)

  def quotationPeriod = specToUse.quotationPeriod

  def indexOption = specToUse.indexOption

  def expiryDay = specToUse.expiryDay
}

case class WeightedPricingSpec(specs: List[(Double, TitanPricingSpec)], valuationCCY : UOM) extends TitanPricingSpec {
  def settlementDay(marketDay: DayAndTime) = specs.flatMap(_._2.settlementDay(marketDay)).sortWith(_ > _).head

  def price(env: Environment) = Quantity.sum(specs.map {
    case (weight, spec) => spec.price(env) * weight
  })

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = specs.map {
    case (wt, spec) => spec.fixedQuantity(marketDay, totalQuantity) * wt
  }.sum

  def isComplete(marketDay: DayAndTime) = specs.forall {
    _._2.isComplete(marketDay)
  }

  def pricingType: String = "Weighted"

  private def quotationPeriodStart: Option[Day] = specs.map(_._2.quotationPeriod).collect {
    case Some(period) => period.firstDay
  }.sortWith(_ < _).headOption

  private def quotationPeriodEnd: Option[Day] = specs.map(_._2.quotationPeriod).collect {
    case Some(period) => period.lastDay
  }.sortWith(_ < _).lastOption

  def premium = specs.map {
    case (wt, spec) => spec.premium * wt
  }.sum

  def daysForPositionReport(marketDay: DayAndTime) = specs.flatMap {
    case (amt, spec) => spec.daysForPositionReport(marketDay)
  }.distinct

  def quotationPeriod = (quotationPeriodStart, quotationPeriodEnd) match {
    case (Some(d1), Some(d2)) => Some(DateRange(d1, d2))
    case _ => None
  }

  def indexOption = None

  def expiryDay = specs.map(_._2.expiryDay).max
}

case class InvalidTitanPricingSpecException(msg: String) extends Exception(msg)

case class FixedPricingSpec(settDay: Day, pricesByFraction: List[(Double, Quantity)],
                            premium: Quantity, valuationCCY : UOM) extends TitanPricingSpec {

  def settlementDay(marketDay: DayAndTime) = settDay

  def price(env: Environment) = {
    val totalFraction = pricesByFraction.map(_._1).sum
    if (totalFraction == 0) {
      throw new InvalidTitanPricingSpecException("Fixed Pricing Spec with no fixed prices")
    } else {
      val fixedPriceComponent = Quantity.sum(pricesByFraction.zipWithIndex.map {
        case ((qty, prc), i) => prc.named("F_" + i) * qty
      }) / totalFraction
      addPremiumConvertingIfNecessary(env, fixedPriceComponent, premium)
    }
  }

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = totalQuantity

  def isComplete(marketDay: DayAndTime) = true

  def pricingType: String = "Fixed"

  def quotationPeriodStart: Option[Day] = None

  def quotationPeriodEnd: Option[Day] = None

  def indexName: String = "No Index"

  def daysForPositionReport(marketDay: DayAndTime) = List(marketDay.day)

  def quotationPeriod = None

  def indexOption = None

  def expiryDay = settDay
}


case class UnknownPricingFixation(fraction: Double, price: Quantity)


case class UnknownPricingSpecification(
                                        index: IndexWithDailyPrices,
                                        month: Month,
                                        fixations: List[UnknownPricingFixation],
                                        declarationDay: Day,
                                        premium: Quantity,
                                        valuationCCY : UOM
                                        )
  extends TitanPricingSpec {

  def settlementDay(marketDay: DayAndTime) = TitanPricingSpec.calcSettlementDay(index, unfixedPriceDay(marketDay))


  private def unfixedPriceDay(marketDay : DayAndTime) = TitanPricingSpec.representativeDay(index, month, marketDay)

  def price(env: Environment) = {
    val totalFixed = fixations.map(_.fraction).sum
    val unfixedFraction = 1.0 - totalFixed
    val fixedPayment = Quantity.sum(fixations.zipWithIndex.map {
      case (f, i) => f.price.named("Fix_" + i) * f.fraction
    }).named("Fixed")
    val unfixedPayment = (index.fixingOrForwardPrice(env, unfixedPriceDay(env.marketDay)) * unfixedFraction).named("Unfixed")
    addPremiumConvertingIfNecessary(env, fixedPayment + unfixedPayment, premium)
  }

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = totalQuantity * fixations.map(_.fraction).sum

  def isComplete(marketDay: DayAndTime) = declarationDay.endOfDay >= marketDay

  def pricingType: String = "Unknown"

  def quotationPeriodStart: Option[Day] = Some(month.firstDay)

  def quotationPeriodEnd: Option[Day] = Some(month.lastDay)

  def indexName: String = index.name

  def daysForPositionReport(marketDay: DayAndTime) = index.observationDays(month).filter(_.endOfDay > marketDay)

  def quotationPeriod = Some(month)

  def indexOption = Some(index)

  def expiryDay = TitanPricingSpec.calcSettlementDay(index, index.observationDays(month).last)
}





