package starling.instrument

import starling.quantity.Quantity
import starling.quantity.Quantity._
import starling.curves.Environment
import starling.market.SingleIndex
import com.trafigura.edm.shared.types.{Quantity => EDMQuantity, CompoundUOM, UnitComponent, FundamentalUOM}
import starling.daterange.{DayOfWeek, Month, Day}
import com.trafigura.tradinghub.support.GUID
import com.trafigura.tradecapture.internal.refinedmetal.{Market => EDMMarket, Metal => EDMMetal}
import starling.edm.{EDMPricingSpecConverter, EDMConversions}
import com.trafigura.edm.materialspecification.CommoditySpec
import com.trafigura.edm.trades.{Trade => EDMTrade, PhysicalTrade => EDMPhysicalTrade}
import java.lang.Exception
import EDMConversions._
import com.trafigura.services.valuation.CostsAndIncomeQuotaValuation
import starling.daterange.DayAndTime

case class InvalidPricingSpecException(msg : String) extends Exception(msg)

trait PricingSpec{
  def quantity : Quantity
  def price(env : Environment) : Quantity
  def settlementDay : Option[Day]
  // This will go once EDM trades have both pricing specs1
  def dummyTransferPricingSpec : PricingSpec
  def addPremiumConvertingIfNecessary(env : Environment, price : Quantity, premium : Quantity) : Quantity = {
    if (premium == Quantity.NULL)
      price
    else {
      val premiumCurrency = premium.uom.numeratorUOM
      val priceCurrency = price.uom.numeratorUOM
      premium + env.forwardFXRate(premiumCurrency, priceCurrency, settlementDay.getOrElse(env.marketDay.day)) * price
    }
  }

  def fixedQuantity(marketDay : DayAndTime) : Quantity
  def isComplete(marketDay : DayAndTime) : Boolean
  def pricingType : String
  def quotationPeriodStart : Option[Day]
  def quotationPeriodEnd : Option[Day]
  def indexName : String
}

trait AveragePricingSpec extends PricingSpec{
  def observationDays : List[Day]
  def index : SingleIndex
  protected def observedDays(marketDay : DayAndTime) = observationDays.filter(_.endOfDay <= marketDay)
  def isComplete(marketDay : DayAndTime) = observedDays(marketDay).size == observationDays.size
  def quotationPeriodStart : Option[Day] = Some(observationDays.head)
  def quotationPeriodEnd : Option[Day] = Some(observationDays.last)
  def indexName : String = index.name
}

case class MonthAveragePricingSpec(quantity : Quantity, index : SingleIndex, month : Month, premium : Quantity) extends AveragePricingSpec{
  val observationDays = index.observationDays(month)
  // Just a guess
  def settlementDay = Some(month.lastDay.addBusinessDays(index.businessCalendar, 2))
  def price(env: Environment) = addPremiumConvertingIfNecessary(env, env.averagePrice(index, month), premium)
  def dummyTransferPricingSpec = copy(premium = Quantity.NULL)
  def fixedQuantity(marketDay : DayAndTime) : Quantity = quantity * observedDays(marketDay).size / observationDays.size
  def pricingType : String = "Month Average"
}

case class PartialAveragePricingSpec(quantity : Quantity, index : SingleIndex, dayFractions : Map[Day, Double], premium : Quantity) extends AveragePricingSpec {

  val observationDays = dayFractions.keySet.toList.sortWith(_<_)
  def settlementDay = Some(dayFractions.keys.toList.sortWith(_>_).head.addBusinessDays(index.businessCalendar, 2))

  def price(env: Environment) = {
    addPremiumConvertingIfNecessary(
      env,
      dayFractions.map {
        case (day, frac) => env.fixingOrForwardPrice(index, day) * frac
      }.sum,
      premium
    )
  }

  def dummyTransferPricingSpec = copy(premium = Quantity.NULL)
  def fixedQuantity(marketDay : DayAndTime) : Quantity = quantity * observationDays.filter(_.endOfDay <= marketDay).map(dayFractions).sum
  def pricingType : String = "Partial Average"
}

case class OptionalPricingSpec(quantity : Quantity, choices : List[PricingSpec], declarationDay : Day, chosenSpec : Option[PricingSpec]) extends PricingSpec {

  private val specToUse = chosenSpec.getOrElse(choices.head)
  def settlementDay = Some(choices.flatMap(_.settlementDay).sortWith(_>_).head)
  def price(env: Environment) = {
    assert(chosenSpec.isDefined || env.marketDay < declarationDay.endOfDay, "Optional pricing spec must be fixed by " + declarationDay)
    specToUse.price(env)
  }

  def dummyTransferPricingSpec = OptionalPricingSpec(quantity, choices.map(_.dummyTransferPricingSpec), declarationDay, chosenSpec.map(_.dummyTransferPricingSpec))
  def fixedQuantity(marketDay : DayAndTime) = specToUse.fixedQuantity(marketDay)
  def isComplete(marketDay : DayAndTime) = specToUse.isComplete(marketDay)
  def pricingType : String = chosenSpec match {case Some(spec) => spec.pricingType; case None => "Optional"}
  def quotationPeriodStart : Option[Day] = specToUse.quotationPeriodStart
  def quotationPeriodEnd : Option[Day] = specToUse.quotationPeriodEnd
  def indexName : String = specToUse.indexName
}

case class WeightedPricingSpec(quantity : Quantity, specs : List[(Double, PricingSpec)]) extends PricingSpec{
  def settlementDay = specs.flatMap(_._2.settlementDay).sortWith(_>_) match {
    case d :: rest => Some(d)
    case Nil => None
  }
  def price(env: Environment) = specs.map{case (weight, spec) => spec.price(env) * weight}.sum

  def dummyTransferPricingSpec = WeightedPricingSpec(quantity, specs.map{case (wt, spec) => (wt, spec.dummyTransferPricingSpec)})
  def fixedQuantity(marketDay : DayAndTime) = specs.map{case (wt, spec) => spec.fixedQuantity(marketDay) * wt}.sum
  def isComplete(marketDay : DayAndTime) = specs.forall{_._2.isComplete(marketDay)}
  def pricingType : String = "Weighted"
  def quotationPeriodStart : Option[Day] = specs.map(_._2.quotationPeriodStart).filter(_.isDefined).map(_.get).sortWith(_<_).headOption
  def quotationPeriodEnd : Option[Day] = specs.map(_._2.quotationPeriodEnd).filter(_.isDefined).map(_.get).sortWith(_<_).lastOption
  def indexName : String = specs.head._2.indexName
}

case class FixedPricingSpec (quantity : Quantity, pricesByQuantity : List[(Quantity, Quantity)]) extends PricingSpec{

  def settlementDay = None

  def price(env: Environment) = {
    val totalQuantity = pricesByQuantity.map(_._1).sum
    if (totalQuantity.isZero){
        throw new InvalidPricingSpecException("Fixed Pricing Spec with no fixed prices")
    } else {
      pricesByQuantity.map{
        case (qty, prc) => qty * prc
      }.sum / totalQuantity
    }
  }

  def dummyTransferPricingSpec = copy()
  def fixedQuantity(marketDay : DayAndTime) = quantity
  def isComplete(marketDay : DayAndTime) = true
  def pricingType : String = "Fixed"
  def quotationPeriodStart : Option[Day] = None
  def quotationPeriodEnd : Option[Day] = None
  def indexName : String = "No Index"
}

case class UnknownPricingFixation(quantity : Quantity, price : Quantity)
case class UnknownPricingSpecification(
  index : SingleIndex,
  month : Month,
  quantity : Quantity,
  fixations : List[UnknownPricingFixation],
  declarationDay : Day,
  premium : Quantity
)
  extends PricingSpec
{

  def settlementDay = Some(month.lastDay.addBusinessDays(index.businessCalendar, 2))

  def price(env: Environment) = {
    val totalFixed = fixations.map(_.quantity).sum
    val thirdWednesday = month.firstDay.dayOnOrAfter(DayOfWeek.wednesday) + 14
    val unfixedPriceDay = if (env.marketDay >= thirdWednesday.endOfDay)
      month.lastDay.thisOrPreviousBusinessDay(index.businessCalendar)
    else
      thirdWednesday
    val unfixedQuantity = quantity - totalFixed
    val fixedPayment = fixations.map{f => f.quantity * f.price}.sum
    val unfixedPayment = env.fixingOrForwardPrice(index, unfixedPriceDay) * unfixedQuantity
    (unfixedPayment + fixedPayment) / quantity
  }

  def dummyTransferPricingSpec = copy(premium = Quantity.NULL)
  def fixedQuantity(marketDay : DayAndTime) = fixations.map(_.quantity).sum
  def isComplete(marketDay : DayAndTime) = declarationDay.endOfDay >= marketDay
  def pricingType : String = "Unknown"
  def quotationPeriodStart : Option[Day] = Some(month.firstDay)
  def quotationPeriodEnd : Option[Day] = Some(month.lastDay)
  def indexName : String = index.name
}


case class PhysicalMetalQuota(
  quotaID : String,
  pricingSpec : PricingSpec,
  // For a purchase this is the expected sale, and vice-versa - TODO find a better name
  expectedTransferPricingSpec : PricingSpec
) {
  def quantity = pricingSpec.quantity
}


object PhysicalMetalForward{
  def apply(exchangesByGUID : Map[GUID, EDMMarket], futuresMetalMarketByGUID : Map[GUID, EDMMetal])(trade : EDMPhysicalTrade) : PhysicalMetalForward = {
    try {
      val quotas : List[PhysicalMetalQuota] = {
        trade.quotas.map(_.detail).map{
          detail =>
            val deliveryQuantity = detail.deliverySpecs.map{ds => fromTitanQuantity(ds.quantity)}.sum
            val commodityGUIDs : Set[GUID] = detail.deliverySpecs.map(_.materialSpec.asInstanceOf[CommoditySpec].commodity).toSet
            assert(commodityGUIDs.size == 1, "Trade " + trade.tradeId + " has multiple commodities")
            val pricingSpec = EDMPricingSpecConverter(futuresMetalMarketByGUID(commodityGUIDs.head), exchangesByGUID).fromEdmPricingSpec(deliveryQuantity, detail.pricingSpec)
            PhysicalMetalQuota(
              detail.identifier,
              pricingSpec,
              pricingSpec.dummyTransferPricingSpec
            )
        }
      }

      val isPurchase = trade.direction match {
        case EDMTrade.PURCHASE => true
        case EDMTrade.SALE => false
        case _ => throw new Exception("Trade " + trade.tradeId + " has no direction " + trade.direction)
      }
      PhysicalMetalForward(trade.tradeId, quotas, isPurchase)
    } catch {
      case ex => throw new Exception("Trade " + trade.tradeId + " failed to construct from EDM. " + ex.getMessage, ex)
    }
  }

  def value(exchangesByGUID : Map[GUID, EDMMarket], futuresMetalMarketByGUID : Map[GUID, EDMMetal], env : Environment, snapshotID : String)(trade : EDMPhysicalTrade) : Either[List[CostsAndIncomeQuotaValuation], String] =  {

    try {
      val forward = PhysicalMetalForward(exchangesByGUID, futuresMetalMarketByGUID)(trade)
      Left(forward.costsAndIncomeValueBreakdown(env, snapshotID))
    } catch {
      case ex => Right("Error valuing trade " + trade.tradeId + ", message was " + ex.getMessage)
    }
  }
}

case class PhysicalMetalForward(tradeID : Int, quotas : List[PhysicalMetalQuota], isPurchase : Boolean) {

  def costsAndIncomeValueBreakdown(env: Environment, snapshotID: String): List[CostsAndIncomeQuotaValuation] = {
    quotas.map {
      quota =>
        val pricingSpec = quota.pricingSpec
        val price = pricingSpec.price(env)
        val transferPrice = quota.expectedTransferPricingSpec.price(env)
        val (purchasePrice, salePrice) =
          if (isPurchase)
            (price, transferPrice)
          else
            (transferPrice, price)

        val value = (salePrice - purchasePrice) * quota.quantity
        CostsAndIncomeQuotaValuation(
          quota.quotaID,
          snapshotID,
          quota.quantity,
          value,
          price,
          transferPrice,
          benchmark = Quantity.NULL,
          freightParity = Quantity.NULL,
          isComplete = pricingSpec.isComplete(env.marketDay),
          fixedQuantity = pricingSpec.fixedQuantity(env.marketDay),
          pricingType = pricingSpec.pricingType,
          quotationPeriodStart = pricingSpec.quotationPeriodStart.map(_.toJodaLocalDate),
          quotationPeriodEnd = pricingSpec.quotationPeriodEnd.map(_.toJodaLocalDate),
          index = pricingSpec.indexName
        )
    }
  }
}
