package starling.services.rpc.refdata

import starling.props.Props
import com.trafigura.services.security.ComponentTestClientExecutor
import org.jboss.resteasy.client.{ProxyFactory, ClientExecutor}
import com.trafigura.tradecapture.internal.refinedmetalreferencedataservice._
import com.trafigura.edm.tradeservice.{EdmGetTradesResource, EdmGetTradesResourceProxy, EdmGetTrades}
import com.trafigura.edm.trades.{PhysicalTrade => EDMPhysicalTrade}
import org.codehaus.jettison.json.JSONObject
import com.trafigura.edm.tradeservice.TradeResults
import com.trafigura.edm.trades.Trade
import com.trafigura.edm.tradeservice.TradeResult
import starling.services.StarlingInit
import com.trafigura.tradinghub.support.GUID
import starling.services.rpc.valuation.ValuationService
import starling.services.rpc.FileUtils
import starling.titan.{TitanEdmTradeService, TitanServices}
import com.trafigura.tradecapture.internal.refinedmetal.{Counterparty, Metal, Market, UOM, Shape, Grade, Location, DestinationLocation}
import com.trafigura.timer.Timer
import com.trafigura.edm.shared.types.TitanId
import scala.collection.immutable.MapProxy

/**
 * Tactical ref data, service proxies / data
 *   also includes the trademgmt EDM trade serivce, this should be refactored to  separate out at some point
 */
case class DefaultTitanServices(props: Props) extends TitanServices {
  val rmetadminuser = props.ServiceInternalAdminUser()
  val tradeServiceURL = props.EdmTradeServiceUrl()
  val refdataServiceURL = props.TacticalRefDataServiceUrl()

  private lazy val clientExecutor: ClientExecutor = new ComponentTestClientExecutor(rmetadminuser)

  private lazy val tacticalRefdataMetalsService: MetalService = new MetalServiceResourceProxy(ProxyFactory.create(classOf[MetalServiceResource], refdataServiceURL, clientExecutor))
  private lazy val tacticalRefdataMarketsService: MarketService = new MarketServiceResourceProxy(ProxyFactory.create(classOf[MarketServiceResource], refdataServiceURL, clientExecutor))
  private lazy val tacticalRefdataCounterpartiesService : CounterpartyService = new CounterpartyServiceResourceProxy(ProxyFactory.create(classOf[CounterpartyServiceResource], refdataServiceURL, clientExecutor))
  private lazy val tacticalRefdataUomService : UOMService = new UOMServiceResourceProxy(ProxyFactory.create(classOf[UOMServiceResource], refdataServiceURL, clientExecutor))
  
  private lazy val tacticalRefdataShapesService : ShapeService = new ShapeServiceResourceProxy(ProxyFactory.create(classOf[ShapeServiceResource], refdataServiceURL, clientExecutor))
  private lazy val tacticalRefdataGradesService : GradeService = new GradeServiceResourceProxy(ProxyFactory.create(classOf[GradeServiceResource], refdataServiceURL, clientExecutor))
  private lazy val tacticalRefdataLocationsService : LocationService = new LocationServiceResourceProxy(ProxyFactory.create(classOf[LocationServiceResource], refdataServiceURL, clientExecutor))
  private lazy val tacticalRefdataDestinationLocationsService : DestinationLocationService = new DestinationLocationServiceResourceProxy(ProxyFactory.create(classOf[DestinationLocationServiceResource], refdataServiceURL, clientExecutor))

  lazy val titanGetEdmTradesService: EdmGetTrades = new EdmGetTradesResourceProxy(ProxyFactory.create(classOf[EdmGetTradesResource], tradeServiceURL, clientExecutor))

  class MapWithNicerErrors[K, V](map : Map[K, V], valueType : String, keyType : String = "GUID") extends MapProxy[K, V]{
    def self = map
    override def apply(k : K) = {
      if (map.contains(k))
        map(k)
      else
        throw new Exception("No " + valueType + " found for " + keyType + ", " + k)
    }
  }

  val defaultMissingKeyExceptionMessage = "Missing key '%s' of type '%s', for values of type '%s'"
  case class RichMap[K : Manifest, V : Manifest](map : Map[K, V]) {
    def withException(s : String = defaultMissingKeyExceptionMessage) : Map[K, V] =
      map.withDefault(k => throw new java.util.NoSuchElementException(s.format(k.toString, manifest[K].erasure.getName, manifest[V].erasure.getName)))
  }
  object RichMap {
    implicit def toRichMap[K : Manifest, V : Manifest](map : Map[K, V]) = RichMap(map)
  }
  import RichMap._

  val edmMetalByGUID = allTacticalRefDataMetals.map(e => e.guid -> e).toMap.withException()
  //val edmMetalByGUID = new MapWithNicerErrors(Map[GUID, Metal]() ++ allTacticalRefDataMetals.map(e => e.guid -> e), "Metal")
  val futuresExchangeByID = allTacticalRefDataExchanges.map(e => e.code -> e).toMap.withException()
  val counterpartiesByGUID = allTacticalRefDataCounterparties().map(e => e.guid -> e).toMap.withException()
  val uomById = allTacticalRefDataUoms().map(e => e.oid -> e).toMap.withException() // hack needed to allow for logistics special "equantity uoms"

  val shapesByGUID = allTacticalRefDataShapes.map(e => e.guid -> e).toMap.withException()
  val gradeByGUID = allTacticalRefDataGrades.map(e => e.guid -> e).toMap.withException()
  val locationsByGUID = allTacticalRefDataLocations.map(e => e.guid -> e).toMap.withException()
  val destLocationsByGUID = allTacticalRefDataDestinationLocations.map(e => e.guid -> e).toMap.withException()

  def allTacticalRefDataMetals() = tacticalRefdataMetalsService.getMetals()
  def allTacticalRefDataExchanges() = tacticalRefdataMarketsService.getMarkets()
  def allTacticalRefDataCounterparties() = tacticalRefdataCounterpartiesService.getCounterparties(true)
  def allTacticalRefDataUoms() = tacticalRefdataUomService.getUOMs()

  def allTacticalRefDataShapes() = tacticalRefdataShapesService.getShapes()
  def allTacticalRefDataGrades() = tacticalRefdataGradesService.getGrades()
  def allTacticalRefDataLocations() = tacticalRefdataLocationsService.getLocations()
  def allTacticalRefDataDestinationLocations() = tacticalRefdataDestinationLocationsService.getDestinationLocations()
}

/**
 * Looks like real ref-data, but really it comes from static data for testing purposes
 */
case class FileMockedTitanServices() extends TitanServices {
   
  import com.trafigura.edm.trades.{PhysicalTrade => EDMPhysicalTrade}
  import starling.services.rpc.FileUtils._

  val resourcePath = "/tests/valuationservice/testdata"
  val tradesFile = getClass.getResource(resourcePath + "/allEdmTrades.json.zip") // "/edmTrades.json")
  val metalsFile = getClass.getResource(resourcePath + "/metals.json")
  val exchangesFile = getClass.getResource(resourcePath + "/exchanges.json")
  val uomsFile = getClass.getResource(resourcePath + "/uoms.json")

  val titanGetEdmTradesService : EdmGetTrades = new EdmGetTrades {
    def getAll() : TradeResults = new TradeResults() {
      cached = true
      results = tradeMap.values.map(t => new TradeResult() {
        trade = t
        error = null
      }).toList
    }
    def get(id : TitanId) : Trade = tradeMap.get(id) match {
      case Some(trade) => trade.asInstanceOf[EDMPhysicalTrade]
      case _ => throw new Exception("Trade does not exist in mock data %s".format(id))
    }
    def getQuota(id : TitanId) = throw new Exception("Not implemented yet") // todo... implement if/when needed
    def getByGuid(guid : GUID) = throw new Exception("Not implemented yet") // todo... implement if/when needed
  }

  lazy val edmMetalByGUID: Map[GUID, Metal] = loadedMetals.map(m => m.guid -> m).toMap
  lazy val futuresExchangeByID: Map[String, Market] = loadedExchanges.map(e => e.code -> e).toMap
  lazy val uomById: Map[Int, UOM] = loadedUoms.map(e => e.oid -> e).toMap
  lazy val counterpartiesByGUID: Map[GUID, Counterparty] = Map[GUID, Counterparty]()

  lazy val shapesByGUID = Map[GUID, Shape]() ++ allTacticalRefDataShapes.map(e => e.guid -> e)
  lazy val gradeByGUID = Map[GUID, Grade]() ++ allTacticalRefDataGrades.map(e => e.guid -> e)
  lazy val locationsByGUID = Map[GUID, Location]() ++ allTacticalRefDataLocations.map(e => e.guid -> e)
  lazy val destLocationsByGUID = Map[GUID, DestinationLocation]() ++ allTacticalRefDataDestinationLocations.map(e => e.guid -> e)
  
  def allTacticalRefDataFuturesMarkets() = Nil
  def allTacticalRefDataExchanges() = Nil

  def allTacticalRefDataShapes() : List[Shape] = Nil
  def allTacticalRefDataGrades() : List[Grade] = Nil
  def allTacticalRefDataLocations() : List[Location] = Nil
  def allTacticalRefDataDestinationLocations() : List[DestinationLocation] = Nil
  
  import Timer._
  val loadedMetals = time(loadJsonValuesFromFileUrl(metalsFile).map(s => Metal.fromJson(new JSONObject(s)).asInstanceOf[Metal]), t => println("took %dms to get metals".format(t)))
  val loadedExchanges = time(loadJsonValuesFromFileUrl(exchangesFile).map(s => Market.fromJson(new JSONObject(s)).asInstanceOf[Market]), t => println("took %dms to get exchanges".format(t)))
  val loadedUoms = time(loadJsonValuesFromFileUrl(uomsFile).map(s => UOM.fromJson(new JSONObject(s)).asInstanceOf[UOM]), t => println("took %dms to get uom".format(t)))
  val loadedTrades = time(loadJsonValuesFromFileUrl(tradesFile, true).map(s => EDMPhysicalTrade.fromJson(new JSONObject(s)).asInstanceOf[EDMPhysicalTrade]), t => println("took %dms to get trades".format(t)))
  var tradeMap = loadedTrades.map(t => t.titanId  -> t).toMap

  def updateTrade(trade : EDMPhysicalTrade) {
    tradeMap = tradeMap.updated(trade.titanId, trade)
  }
}

case class FileMockedTitanServicesDataFileGenerator(titanEdmTradeService : TitanEdmTradeService, valuationService : ValuationService) {

  import org.codehaus.jettison.json.JSONObject
  import FileUtils._

  val tradesFile = "/tmp/allEdmTrades.json.zip"
  val metalsFile = "/tmp/metals.json"
  val exchangesFile = "/tmp/exchanges.json"
  val uomsFile = "/tmp/uoms.json"

  println("Starting FileMockedTitanServicesDataFileGenerator")

  val metals = valuationService.getMetals.toList
  val exchanges = valuationService.getFuturesExchanges.toList
  val uoms = valuationService.getUoms.toList

  writeJson(metalsFile, metals)
  writeJson(exchangesFile, exchanges)
  writeJson(uomsFile, uoms)

  val loadedMetals = loadJsonValuesFromFile(metalsFile).map(s => Metal.fromJson(new JSONObject(s)).asInstanceOf[Metal])
  loadedMetals.foreach(println)
  val loadedExchanges = loadJsonValuesFromFile(exchangesFile).map(s => Market.fromJson(new JSONObject(s)).asInstanceOf[Market])
  loadedExchanges.foreach(println)
  val loadedUoms = loadJsonValuesFromFile(uomsFile).map(s => UOM.fromJson(new JSONObject(s)).asInstanceOf[UOM])
  loadedUoms.foreach(println)

  /**
   * get edm trades and store in mock data file
   */
  valuationService.marketDataSnapshotIDs().foreach(println)
  val valuations = valuationService.valueAllQuotas()

//  valuations.tradeResults.foreach(println)

  val (worked, failed) = valuations.tradeResults.values.partition(_ isRight)
  failed.foreach(println)
  val tradeIds = valuations.tradeResults.collect{ case (id, Right(_)) => id }.toList
  //val trades = valuationService.getTrades(tradeIds)
  val trades = titanEdmTradeService.titanGetEdmTradesService.getAll().results.map(_.trade).filter(_ != null)

  println("read %d trades ".format(trades.size))

  writeJson(tradesFile, trades, true)

  val loadedTrades = loadJsonValuesFromFile(tradesFile, true).map(s => EDMPhysicalTrade.fromJson(new JSONObject(s)).asInstanceOf[EDMPhysicalTrade])
  println("loaded %d trades = ".format(loadedTrades.size))
}

object RefDataServices {

  def main(args : Array[String]) {
    println("running main for tactical ref data services")
    val server = StarlingInit.devInstance
    val edmTradeService = server.titanServices
    val valuationService = server.valuationService
    FileMockedTitanServicesDataFileGenerator(edmTradeService, valuationService)
    server.stop
  }
}
