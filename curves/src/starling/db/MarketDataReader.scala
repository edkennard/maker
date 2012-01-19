package starling.db

import starling.curves.MissingMarketDataException
import starling.daterange.{ObservationTimeOfDay, Day, ObservationPoint}
import starling.marketdata._

import starling.utils.ImplicitConversions._
import starling.pivot.Row
import collection.Traversable


trait MarketDataReader {
  //Abstract
  def identifier:String

  def marketDataTypes:MarketDataTypes

  def read(marketDataType: MarketDataTypeName, observationDays: Option[Set[Option[Day]]] = None,
           observationTimes: Option[Set[ObservationTimeOfDay]] = None,
           keys: Option[Set[MarketDataKey]]=None): List[(TimedMarketDataKey, MarketDataRows)]

  def readAllObservationDayAndMarketDataKeys(marketDataType: MarketDataTypeName): List[TimedMarketDataKey]

  def readAll(marketDataType: MarketDataTypeName, observationPoint:ObservationPoint): List[(MarketDataKey, MarketDataRows)] = read(
    marketDataType,
    observationDays = Some(Set(observationPoint.day)),
    observationTimes = Some(Set(observationPoint.timeOfDay))
  ).mapFirst(_.key)

  def readAll[MDT <: MarketDataType](dataType: MDT, observationPoint: ObservationPoint): List[(MDT#keyType, MDT#dataType)] = {
    readAll(dataType.name, observationPoint).collect {
      case (key: MDT#keyType, rows) => key → rows.toMarketData(dataType)
    }
  }

  //Helper concrete methods
  def read(timedKey: TimedMarketDataKey): MarketData = {
    val key = timedKey.key
    val observationPoint = timedKey.observationPoint
    val marketDataType = marketDataTypes.fromName(key.typeName)
    read(key.typeName, Some(Set(observationPoint.day)), Some(Set(observationPoint.timeOfDay)), Some(Set(key))).headOption match {
      case Some((_,rows)) => {
        marketDataType.createValue(rows.rows.map(Row(_))).asInstanceOf[MarketData]
      }
      case None => {
        val marketDataType = marketDataTypes.fromName(key.typeName)
        val short = "No " + timedKey.key.humanName + " " + marketDataType.humanName
        throw new MissingMarketDataException(short, short + " found on " + observationPoint.unparse)
      }
    }
  }

  def readMostRecent(numberOfDaysToLookBack : Int, observationDay : Day, timeOfDay : ObservationTimeOfDay, key : MarketDataKey) : MarketData = {
    def tryToRead(day : Day) : MarketData = try {
      read(TimedMarketDataKey(ObservationPoint(day, timeOfDay), key))
    } catch {
      case _: MissingMarketDataException if observationDay - day.previousWeekday <= numberOfDaysToLookBack => tryToRead(day.previousWeekday)
      case e => throw e
    }
    tryToRead(observationDay)
  }

  def readAs[T <: MarketData](timedKey: TimedMarketDataKey) = read(timedKey).asInstanceOf[T]

  def readAllPrices(observationPoint: ObservationPoint): List[(PriceDataKey, PriceData)] =
    readAll(PriceDataType, observationPoint)

  def readAllVols(observationPoint: ObservationPoint): List[(OilVolSurfaceDataKey, OilVolSurfaceData)] =
    readAll(OilVolSurfaceDataType, observationPoint)
}
