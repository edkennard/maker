package starling.services.rpc.marketdata

import starling.titan.EDMConversions._
import starling.marketdata.{SpotFXDataType, MarketDataType, PriceFixingsHistoryDataType}
import starling.pivot._
import starling.pivot.model.PivotTableModel
import starling.utils.ImplicitConversions._
import PriceFixingsHistoryDataType._
import SpotFXDataType._
import starling.quantity.{Percentage, Quantity, UOM}

import com.trafigura.services._
import com.trafigura.services.marketdata.{MarketDataServiceApi, ReferenceInterestRate, ReferenceRateSource, Maturity}
import starling.utils.Log
import collection.immutable.List
import starling.services.Server
import valuation.TitanSnapshotIdentifier
import starling.daterange.{DateRange, Tenor, Day}
import org.joda.time.LocalDate
import starling.services.rpc.valuation.{DefaultEnvironmentProvider, EnvironmentProvider}
import starling.gui.api._
import starling.db.{SnapshotID, MarketDataStore}

class MarketDataService(marketDataStore: MarketDataStore, environmentProvider: EnvironmentProvider)
  extends MarketDataServiceApi with DocumentedService with Log {

  type Matcher[T] = T => Boolean

  implicit def enrichReferenceInterestRate(self: ReferenceInterestRate) = new {
    def matches(observationDate: Matcher[TitanSerializableDate], source: Matcher[ReferenceRateSource], maturity: Matcher[Maturity],
                currency: Matcher[TitanSerializableCurrency], rate: Matcher[TitanSerializablePercentage])

    = observationDate(self.observationDate) && source(self.source) && maturity(self.maturity) && currency(self.currency)
  }

  /**
   * Return all snapshots for a given observation day, or every snapshot if no day is supplied
   */
  def marketDataSnapshotIDs(observationDay: Option[LocalDate] = None): List[TitanSnapshotIdentifier] = {
    environmentProvider.snapshotIDs(observationDay.map(Day.fromJodaDate)).map {
      starlingSnapshotID => TitanSnapshotIdentifier(starlingSnapshotID.id.toString, starlingSnapshotID.observationDay.toJodaLocalDate)
    }
  }


  def getSpotFXRate(snapshotId: TitanSnapshotIdentifier, from: TitanSerializableCurrency, to: TitanSerializableCurrency)
    : TitanSerializableQuantity = getSpotFXRate(from.fromSerializable, to.fromSerializable, snapshotId).toSerializable

  def getSpotFXRates(snapshotId: TitanSnapshotIdentifier): List[TitanSerializableQuantity] =
    getSpotFXRatesImpl(snapshotId).map(_.toSerializable)

  def getReferenceInterestRate(snapshotId: TitanSnapshotIdentifier, source: ReferenceRateSource, maturity: Maturity,
                               currency: TitanSerializableCurrency): ReferenceInterestRate = {
    snapshotId.notNull("snapshotId"); source.notNull("source"); maturity.notNull("maturity"); currency.notNull("currency")

    getReferenceInterestRates(snapshotId.toTitanDate, snapshotId.toTitanDate, source, maturity, currency)(0)
  }

  def getReferenceInterestRates(from: TitanSerializableDate, to: TitanSerializableDate, source: ReferenceRateSource,
                                maturity: Maturity, currency: TitanSerializableCurrency) = {
    from.notNull("from"); to.notNull("to"); source.notNull("source"); maturity.notNull("maturity"); currency.notNull("currency")

    val results = getReferenceInterestRates(None, from.fromSerializable upto to.fromSerializable).filter(_.matches(
      ignore[TitanSerializableDate], eql(source), eql(maturity), eql(currency), ignore[TitanSerializablePercentage]))

    if (results.isEmpty) throw new IllegalArgumentException(
      "No Reference Interest Rate observed between %s-%s with source: %s, maturity: %s, currency: %s" %
        (from, to, source, maturity, currency) +
      ("\n\tvalid sources: %s\n\tmaturities: %s\n\tcurrencies: %s" %
        (results.map(_.source.name).distinct, results.map(_.maturity).distinct, results.map(_.currency.name).distinct)))

    results
  }

  def getReferenceInterestRates(snapshotId: TitanSnapshotIdentifier) = getReferenceInterestRates(snapshotId.intId, snapshotId.toDay)

  private def getReferenceInterestRates(version: Option[Int], observationDates: DateRange): List[ReferenceInterestRate] = {
    val response = getMarketData(fixingRequest.copyObservationDays(observationDates).copyVersion(version).copyExchange("LIBOR")
      .copy(rows = List(new FieldDetails("Observation Day"), exchangeField, marketField, periodField)))

    response.data.map(_.map(_.toString)).collect {
      case List(Day(date), exchange, TitanSerializableCurrency.Parse(currency), Tenor.Parse(tenor), Percentage.Parse(percentage)) =>
        ReferenceInterestRate(date.toTitan, ReferenceRateSource(exchange.toString), tenor.toTitan, currency, percentage.toSerializable)
    }
  }

  private def ignore[T] = (input: T) => true
  private def eql[T](equalTo: T) = (input: T) => equalTo == input

  private def getSpotFXRate(from: UOM, to: UOM, snapshotId: TitanSnapshotIdentifier): Quantity = {
    val rates = getSpotFXRatesImpl(snapshotId).toMapWithKeys(_.uom.denominatorUOM) + (UOM.USD → Quantity(1, UOM.SCALAR))

    (rates.get(from), rates.get(to)) match {
      case (Some(from), Some(to)) => from / to
      case _ => throw new IllegalArgumentException("No Spot FX Rate for %s/%s observed on %s, valid currencies: [%s]" %
        (from, to, snapshotId, rates.keySet.mkString(", ")))
    }
  }

  def fromTitan(titanSnapshotIdentifier: TitanSnapshotIdentifier): SnapshotIDLabel = {
    marketDataStore.snapshotFromID(titanSnapshotIdentifier.intId).getOrElse(throw new Exception("Invalid snapshot id")).label
  }

  private def getSpotFXRatesImpl(snapshotId: TitanSnapshotIdentifier): List[Quantity] =
    getMarketData(spotFXRequest.copySnapshotId(fromTitan(snapshotId))).data.map(_.map(_.toString)).collect {
      case List(_, Quantity.Parse(rate)) => if (rate.denominatorUOM == UOM.USD) rate.invert else rate
    }

  private def getMarketData(parameters: MarketDataRequestParameters): MarketDataResponse = log.infoF("getMarketData: " + parameters) {
    parameters.notNull("Missing parameters")

    val selection = MarketDataSelection(Some(parameters.pricingGroup))
    val version: MarketDataVersion = parameters.version.getOrElse(SpecificMarketDataVersion(marketDataStore.latest(selection)))
    val pivot = marketDataStore.pivot(MarketDataIdentifier(selection, version), parameters.dataType)
    val pfs = parameters.pivotFieldsState(pivot)
    val data = PivotTableModel.createPivotTableData(pivot, Some(pfs)).toFlatRows(Totals.Null, trimBlank = true)

    MarketDataResponse(parameters.copy(version = Some(version)), data)
  }

  private val fixingRequest = MarketDataRequestParameters(PricingGroup.Metals, PriceFixingsHistoryDataType, None,
    measures = List(priceField), filters = Map("Observation Day" → List(Day.today.toString)))

  private val spotFXRequest = MarketDataRequestParameters(PricingGroup.Metals, SpotFXDataType, None,
    measures = List(rateField), rows = List(currencyField))
}

case class MarketDataResponse(parameters: MarketDataRequestParameters, data: List[List[Any]])

case class MarketDataRequestParameters(pricingGroup: PricingGroup, dataType: MarketDataType,
  version: Option[MarketDataVersion] = None, measures: List[FieldDetails] = Nil, filters: Map[String, List[String]] = Map(),
  rows: List[FieldDetails] = Nil, columns: List[FieldDetails] = Nil) {

  def copyVersion(version: Option[Int]) = copy(version = version.map(SpecificMarketDataVersion(_)))
  def copyExchange(exchangeNames: String*) = addFilter(exchangeField.name, exchangeNames: _*)
  def copyObservationDay(day: Day) = addFilter("Observation Day", day.toString)
  def copyObservationDays(dateRange: DateRange) = addFilter("Observation Day", dateRange.days.map(_.toString) : _*)
  def copySnapshotId(snapshotId: SnapshotIDLabel) =
    copy(version = Some(SnapshotMarketDataVersion(snapshotId))).copyObservationDay(snapshotId.observationDay)
  def addFilter(name: String, values: String*) = copy(filters = filters + (name → values.toList))

  def pivotFieldsState(pivot: PivotTableDataSource) =
    PivotFieldsState(fields(measures), fields(rows), fields(columns), filters.map {
      case (name, values) => pivot.parseFilter(Field(name), values)
    }.toList)

  private def fields(names: List[FieldDetails]) = names.map(_.field)
}