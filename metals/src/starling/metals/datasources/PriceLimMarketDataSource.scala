package starling.metals.datasources

import collection.immutable.List

import starling.daterange._
import starling.marketdata._
import starling.utils.ImplicitConversions._
import starling.utils.Pattern.Extractor
import starling.db.{MarketDataStore, MarketDataEntry}
import starling.pivot.PivotQuantity
import starling.databases._
import starling.market._
import starling.gui.api._
import scalaz.Scalaz._
import starling.scheduler.{ScheduledTask, ScheduledTime, TaskDescription}
import starling.lim.{LIMService, LimNode, LIMConnection}
import starling.services.EmailService
import starling.curves.readers.VerifyMarketDataAvailable


object PriceLimMarketDataSource extends scalaz.Options {
  import LIMService.TopRelation.Trafigura.Bloomberg._

  val priceSources = PriceDataType.name → List(
    new PriceLimSource(new LMELIMRelation(Metals.Lme, FuturesExchangeFactory.LME)),
    new PriceLimSource(new MonthlyLIMRelation(Futures.Comex, FuturesExchangeFactory.COMEX)),
    new PriceLimSource(new MonthlyLIMRelation(Futures.Shfe, FuturesExchangeFactory.SFS)))

  class LMELIMRelation(val node: LimNode, override val exchange: FuturesExchange) extends LIMRelation {
    private lazy val lmeMarkets = FuturesExchangeFactory.LME.marketsByCommodityName + "steelbillet" → Market.LME_STEEL_BILLETS

    protected val extractor = Extractor.regex[Option[LimPrice]]("""TRAF\.LME\.(\w+)\.(\d+)\.(\d+)\.(\d+)""") {
      case List(commodity, y, m, d) => some(LimPrice(lmeMarkets(commodity.toLowerCase), Day(y, m, d), exchange.closeTime))
    }
  }

  class MonthlyLIMRelation(val node: LimNode, override val exchange: FuturesExchange) extends LIMRelation {
    protected val extractor = Extractor.regex[Option[LimPrice]]("""TRAF\.%s\.(\w+)_(\w+)""" % exchange.limName) {
      case List(lim, deliveryMonth) =>
      val optMarket = exchange.markets.find(_.limSymbol == Some(lim))
      val optMonth = ReutersDeliveryMonthCodes.parse(deliveryMonth)

      (optMarket, optMonth) match {
        case (Some(market), Some(month)) => Some(LimPrice(market, month, exchange.closeTime))
        case (None, _) => debug("No Market with exchange: %s and LIM Symbol: %s" % (exchange.limName, lim))
        case (_, None) => debug("Cannot parse Reuters delivery month: " + deliveryMonth)
      }
    }
  }
}

case class PriceLimMarketDataSource(service: LIMService, bloombergImports: BloombergImports, emailService: EmailService, template: Email)
  extends LimMarketDataSource(service) {

  import PriceLimMarketDataSource._
  import ScheduledTime._
  import FuturesExchangeFactory._

  override def description = List(priceSources).flatMap
    { case (marketDataType, sources) => marketDataType.name.pair(sources.flatMap(_.description)).map("%s → %s" % _) }

  def read(day: Day) = log.infoWithTime("Getting data from LIM") {
    Map(getValuesForType(PriceDataType.name, day.startOfFinancialYear, day, priceSources))
  }

  override def eventSources(marketDataStore: MarketDataStore) = limMetalMarketsForExchanges.map(markets =>
    PriceDataEventSource(PricingGroup.Metals, PriceMarketDataProvider(marketDataStore, markets)))

  override def availabilityTasks(marketDataStore: MarketDataStore) = List(
    task("LME Metals", daily(LME.calendar, 20 H 00), lme, marketDataStore),
    task("COMEX Metals", daily(COMEX.calendar, 15 H 30), comex, marketDataStore),
    task("SHFE Metals", daily(SFS.calendar, 15 H 00), shfe, marketDataStore)
  )

//  val exbxgMetals = new DataFlow(FuturesExchangeFactory.EXBXG, PricingGroup.Metals, Nil, metalsEmail, wuXiEmail, "Excel",
//    "WuXi Metals") with NullMarketDataEventSource
//      tasks(daily(SFE, 16 H 30), availabilityBroadcaster.verifyPricesAvailable(exbxgMetals), verifyPricesValid(exbxgMetals))

  private lazy val limMetalMarketsForExchanges@List(lme, comex, shfe) = priceSources._2.map(pricesSource => {
    val one: List[FuturesMarket] = pricesSource.exchange.markets
    val two: List[FuturesMarket] = one.filter(_.limSymbol.isDefined)
    val three = two.filter(isMetal)
    val four = three.filter(correspondsToBloombergImport(pricesSource.node))

    four
  } )

  //require(lme != Nil, "LME"); require(comex != Nil, "COMEX"); require(shfe != Nil, "SHFE")

  private def isMetal(market: FuturesMarket) = Commodity.metalsCommodities.contains(market.commodity)
  private def correspondsToBloombergImport(node: LimNode)(market: FuturesMarket) = bloombergImports.matches(node.name, market)

  private def task(name: String, time: ScheduledTime, markets: List[CommodityMarket], marketDataStore: MarketDataStore): TaskDescription =
    TaskDescription("Verify %s Prices Available" % name, time, task(name, marketDataStore, markets))

  private def task(name: String, marketDataStore: MarketDataStore, markets: List[CommodityMarket]): ScheduledTask =
    new VerifyMarketDataAvailable(marketDataStore, MarketDataSelection(Some(PricingGroup.Metals)), PriceDataType.name,
      markets.map(PriceDataKey(_)).toSet, emailService, template) {

      override protected def emailFor(observationDay: Day) = queryLatest(observationDay) |> { _ match {
        case Nil => Some(template.copy(subject = "No Prices for: %s on %s" % (name, observationDay),
                                       body = markets.map("MISSING: " + _).sorted.mkHtml()))
        case prices => {
          val marketAvailability = {
            val availableMarkets = prices.map(_._1.key.asInstanceOf[PriceDataKey].market).toSet

            markets.groupBy(availableMarkets.contains(_) ? "Present" | "MISSING")
          }

          marketAvailability.contains("MISSING").option {
            template.copy(subject = "Missing Prices for: %s on %s" % (name, observationDay),
                          body = marketAvailability.flatMultiMap { _.format("%s: %s") }.toList.sorted.mkHtml())
          }
        }
      } }
    }
}

class PriceLimSource(relation: LIMRelation) extends LimSource(List(Level.Close)) {
  val (node, nodes, exchange) = (relation.node, List(relation.node), relation.exchange)
  type Relation = LimPrice
  def description = nodes.map(_.name + " " + levelDescription)

  def relationsFrom(connection: LIMConnection) =
    connection.getAllRelChildren(relation.node).flatMap(childRelation => relation.parse(childRelation).optPair(childRelation))

  def marketDataEntriesFrom(allPrices: List[Prices[LimPrice]]) = allPrices.groupBy(group _)
    .map { case ((market, observationPeriod), prices) => MarketDataEntry(observationPeriod, PriceDataKey(market),
        PriceData.create(prices.map(price => {
          val limMultiplier = market.limSymbol match {
            case Some(ls) => ls.multiplier
            case None => 1.0
          }
          price.relation.period → (price.priceFor(Level.Close) * limMultiplier)
        }), market.priceUOM))
    }

  private def group(prices: Prices[LimPrice]) =
    prices.relation.market → prices.atTimeOfDay(prices.relation.observationTimeOfDay)
}

case class PriceDataEventSource(pricingGroup: PricingGroup, provider: MarketDataProvider[CommodityMarket, DateRange, PivotQuantity])
  extends PricingGroupMarketDataEventSource {

  type Key = CommodityMarket
  type MarketType = DateRange
  type CurveType = PivotQuantity

  protected def marketDataEvent(change: MarketDataChange, market: CommodityMarket, periods: List[DateRange], snapshot: SnapshotIDLabel) = {
    Some(PriceDataEvent(change.observationDay, market.name, periods, snapshot, change.isCorrection))
  }

  protected def marketDataProvider = Some(provider)
}

case class PriceMarketDataProvider(marketDataStore: MarketDataStore, markets: List[CommodityMarket])
  extends AbstractMarketDataProvider[CommodityMarket, DateRange, PivotQuantity](marketDataStore) {

  val marketDataType = PriceDataType.name
  val marketDataKeys = markets.ifDefined(_.map(PriceDataKey(_).asInstanceOf[MarketDataKey]).toSet)

  def marketDataFor(timedData: List[(TimedMarketDataKey, MarketData)]) = timedData.collect {
    case (TimedMarketDataKey(_, PriceDataKey(market)), PriceData(prices)) => (market, prices)
  }.toMap
}