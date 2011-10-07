package starling.db

import starling.quantity.Percentage
import starling.marketdata._
import starling.utils.ImplicitConversions._
import starling.richdb.RichResultSetRow
import starling.utils.cache.CacheFactory
import starling.daterange._
import starling.instrument.utils.StarlingXStream
import starling.utils.Pattern._
import java.lang.String
import starling.utils._
import scalaz._
import Scalaz._
import collection.immutable.{Iterable, Map, TreeMap}
import sql.PersistAsBlob
import sql.PersistAsBlob._
import starling.pivot.{PivotEdits, PivotTableDataSource, Field => PField}
import starling.dbx._
import collection.mutable.{ListBuffer, HashSet => MSet}
import starling.gui.api._
import starling.props.Props
import scala.concurrent.SyncVar
import QueryBuilder._

// TODO [07 Sep 2010] move me somewhere proper
case class RelativeImpliedVolData(vols: Map[DateRange, Map[Double, Percentage]]) {
  def absolute(prices: PriceData): ImpliedVolData = {
    val data = vols.flatMap {
      case (period, row) => {
        // lame hack - why, trinity? WHY???
        val exerciseDay = period.firstDay - 2
        val strike: Double = prices.prices(exerciseDay).quantityValue.get.value
        row.map((tuple: (Double, Percentage)) => {
          (ImpliedVolEntryKey(period, strike + tuple._1, exerciseDay), tuple._2)
        })
      }
    }
    ImpliedVolData(TreeMap.empty[ImpliedVolEntryKey, Percentage](ImpliedVolEntryKey) ++ data)
  }
}

/**
 * Thrown when there is no market data available for a particular day
 */
case class NoMarketDataForDayException(observationDay: Day, m: String) extends Exception(m)



case class MarketDataSet(name: String, priority: Int) {
  def isExcel = name.startsWith(MarketDataSet.excelPrefix)
  def stripExcel = name.stripPrefix(MarketDataSet.excelPrefix)
}

object MarketDataSet extends StarlingEnum(classOf[MarketDataSet], (m: MarketDataSet) => m.name) {
  val excelPrefix = "Excel:"

  def excel(name: String) = if (name == "Official:Metals") MarketDataSet.ManualMetals else {
    MarketDataSet(name.prefixWith(excelPrefix), Integer.MAX_VALUE)
  }

  def fromExcel(marketDataSet: MarketDataSet) = if (!marketDataSet.name.startsWith(excelPrefix)) None else {
    Some(marketDataSet.name.stripPrefix(excelPrefix))
  }

  override def fromName(name: String): MarketDataSet = if (name.startsWith(excelPrefix)) MarketDataSet(name, Integer.MAX_VALUE) else {
    super.fromName(name)
  }

  val LIM = MarketDataSet("LIM", 100) //Oil and Metals VAR
  val System = MarketDataSet("System", 200)
  val Crude = MarketDataSet("Crude", 300)
  val LondonDerivatives = MarketDataSet("London Derivatives", 400)
  val GasolineRoW = MarketDataSet("Gasoline RoW", 500)
  val GasOil = MarketDataSet("Gas Oil", 501)
  val Naphtha = MarketDataSet("Naphtha", 502)
  val BarryEckstein = MarketDataSet("Barry Eckstein", 600)
  val LondonDerivativesOptions = MarketDataSet("London Derivatives Options", 700)
  val LimMetals = MarketDataSet("LimMetals", 800) //Refined Metals
  // val Neptune = MarketDataSet("Neptune", 1000)
  val ManualMetals = MarketDataSet("ManualMetals", 1100)
  val Starling = MarketDataSet("Starling", 1200)
}

object MarketDataStore {

  import MarketDataSet._

  val pricingGroupsDefinitions = MultiMap[PricingGroup, MarketDataSet](
    PricingGroup.Metals ->> (ManualMetals, LimMetals),
    PricingGroup.LimOnly ->> LIM,
    PricingGroup.System ->> (Starling, LIM, System),
    PricingGroup.Crude ->> (Starling, LIM, Crude),
    PricingGroup.LondonDerivatives ->> (Starling, LondonDerivatives, LIM),
    PricingGroup.GasolineRoW ->> (Starling, GasolineRoW, LIM),
    PricingGroup.GasOil ->> (Starling, GasOil, LIM),
    PricingGroup.Naphtha ->> (Starling, Naphtha, GasolineRoW, LIM),
    PricingGroup.BarryEckstein ->> (Starling, BarryEckstein, System, LIM),
    PricingGroup.LondonDerivativesOptions ->> (Starling, LondonDerivativesOptions, System, LIM)
  )

  val manuallyEditableMarketDataSets = Set(ManualMetals, Starling)
  val pricingGroups = pricingGroupsDefinitions.keySet.toList
  def pricingGroupForName(name: String): PricingGroup = pricingGroups.find(_.name == name).get

  def editableMarketDataSetFor(pricingGroup: PricingGroup) = pricingGroupsDefinitions.get(pricingGroup).flatMap {
    sets => (manuallyEditableMarketDataSets & sets.toSet).headOption
  }

  def applyOverrideRule(marketDataType: MarketDataType, allDataForKeyAndDay: List[Map[PField, Any]]): List[Map[PField, Any]] = {
    val dataAsMaps: Map[Map[PField, Any], Map[PField, Any]] = Map() ++ allDataForKeyAndDay.map(marketDataType.splitByFieldType(_))
    val m = scala.collection.mutable.HashMap[Map[PField, Any], Map[PField, Any]]()

    dataAsMaps.foreach { case (k, v) => {
      m(k) = v
    } }

    m.map { case (k, v) => k ++ v }.toList
  }
}

/**
 * Wraps a data type to provide version information.
 */

trait MarketDataStore {
  val pricingGroupsDefinitions = MarketDataStore.pricingGroupsDefinitions
  val pricingGroups = MarketDataStore.pricingGroups

  def readAll()

  def excelDataSets: List[String]

  def importData(marketDataSelection: MarketDataSelection, observationDay: Day): SaveResult
  def importFor(observationDay: Day, marketDataSets: MarketDataSet*): SaveResult

  def latest(selection: MarketDataSelection): Int

  def latestExcelVersions: Map[MarketDataSet, Int]

  def latestMarketDataIdentifier(selection: MarketDataSelection): MarketDataIdentifier
  def identifierFor(selection: MarketDataSelection, version: Option[MarketDataVersion]) = version.fold(
    v => MarketDataIdentifier(selection, v), latestMarketDataIdentifier(selection))

  def latestObservationDayForMarketDataSet(marketDataSet: MarketDataSet): Option[Day]
  def latestObservationDayFor(pricingGroup: PricingGroup, marketDataType: MarketDataType): Day

  def latestPricingGroupVersions: Map[PricingGroup, Int]

  def latestSnapshot(pricingGroup: PricingGroup, observationDay: Day): Option[SnapshotID]
  def latestSnapshot(pricingGroup: PricingGroup): Option[SnapshotID]

  def marketData(from: Day, to: Day, marketDataType: MarketDataType, marketDataSet: MarketDataSet): Map[TimedMarketDataKey, VersionedMarketData]

  def marketDataTypes(marketDataIdentifier: MarketDataIdentifier): List[MarketDataType]

  def observationDaysByExcel(): Map[String, Set[Day]]
  def observationDaysByPricingGroup(): Map[PricingGroup, Set[Day]]

  def pivot(marketDataIdentifier: MarketDataIdentifier, marketDataType: MarketDataType, pivotEdits:PivotEdits=PivotEdits.Null): PivotTableDataSource

  def pivot(selection: MarketDataSelection, marketDataType: MarketDataType): PivotTableDataSource

  def query(marketDataIdentifier: MarketDataIdentifier, marketDataType: MarketDataType,
            observationDays: Option[Set[Option[Day]]] = None, observationTimes: Option[Set[ObservationTimeOfDay]] = None,
            marketDataKeys: Option[Set[MarketDataKey]] = None): List[(TimedMarketDataKey, MarketData)]

  def queryForObservationDayAndMarketDataKeys(marketDataIdentifier: MarketDataIdentifier, marketDataType: MarketDataType): List[TimedMarketDataKey]

  def readLatest(marketDataSet: MarketDataSet, timedKey: TimedMarketDataKey): Option[VersionedMarketData]

  def save(marketDataSetToData: MultiMap[MarketDataSet, MarketDataEntry]): SaveResult

  def save(marketDataSet: MarketDataSet, timedKey: TimedMarketDataKey, marketData: MarketData): Int

  def saveAll(marketDataSet: MarketDataSet, observationPoint: ObservationPoint, data: Map[MarketDataKey, MarketData]): SaveResult

  def update(marketDataSetToData: Map[MarketDataSet, Iterable[MarketDataUpdate]]): SaveResult

  def snapshot(marketDataSelection: MarketDataSelection, doImport: Boolean, observationDay: Day): Option[SnapshotID]

  def snapshots(): List[SnapshotID]

  def snapshotsByMarketDataSelection(): MultiMap[MarketDataSelection, SnapshotIDLabel]

  def snapshotFromID(snapshotID: Int): Option[SnapshotID]
  def snapshotFromID(snapshotID: Option[Int]): Option[SnapshotID] = snapshotID.map(snapshotFromID(_)).flatOpt

  def sourcesFor(marketDataSet: MarketDataSet): List[MarketDataSource]
  def sourcesFor(pricingGroup: PricingGroup): List[MarketDataSource]
}

case class SaveResult(maxVersion: Int, anythingChanged: Boolean, affectedObservationDays: Option[List[Day]] = None)

case class VersionedMarketData(version: Int, data: Option[MarketData])

object VersionedMarketData {
  def apply(version: Int, marketData: MarketData): VersionedMarketData =
    if (marketData.size == 0) VersionedMarketData(version, None) else VersionedMarketData(version, Option(marketData))

  val Delete = Extractor.when[VersionedMarketData](_.data.isEmpty)
  val Save = Extractor.when[VersionedMarketData](_.data.isDefined)
}

case class MarketDataEntry(observationPoint: ObservationPoint, key: MarketDataKey, data: MarketData, tag: Option[String] = None) {
  val dataType = key.dataType

  def isEmpty = data.size == 0 // key.castRows(data, ReferenceDataLookup.Null).isEmpty

  def toSave(existingData: Option[VersionedMarketData]) = !isEmpty option(MarketDataUpdate(timedKey, Some(data), existingData, tag))

  def toUpdate(existingData: Option[VersionedMarketData]) = toSave(existingData).getOrElse(MarketDataUpdate(timedKey, None, existingData, tag))

  def timedKey = TimedMarketDataKey(observationPoint, key)

  def dataIdFor(marketDataSet: MarketDataSet) = MarketDataID(timedKey, marketDataSet)
}

case class MarketDataUpdate(timedKey: TimedMarketDataKey, data: Option[MarketData], existingData: Option[VersionedMarketData],
                            tag: Option[String] = None) {

  def observationPoint = timedKey.observationPoint
  def marketDataKey = timedKey.key
  def dataIdFor(marketDataSet: MarketDataSet) = MarketDataID(timedKey, marketDataSet)
  def indexedData(marketDataSet: MarketDataSet) = dataIdFor(marketDataSet) → data
}



//class FastMdDB(db: DBTrait[RichResultSetRow]) extends MdDB {
//
//  //read early:
//  //all observation days
//  //all Key1 (mds, time, key)
//  //all Key2
//
//
//  //will have (Day) -> (MDS,ObsTime,MarketDataKey) -> (SecondKey) -> Version -> (Option[Value],Timestamp,User)
//
//
//  def checkIntegrity():Unit = {}
//  def readAll():Unit  = {}
//
//  def marketDataSetNames():List[String]  = {}// "select distinct marketDataSet from Key2"
//
//  // "select observationDay from Values join Key1 on .. where mds =
//  def observationDaysFor(marketDataSets:List[MarketDataSet]):List[Option[Day]]  = {}
//  def excelObservationDays():List[(String,Option[Day])] = {}
//
//   //"select max(commitid), mds from Values join on Key1 ,,,"
//  def latestVersionForMarketDataSets():Map[MarketDataSet,Int]
//  def latestExcelVersions():Map[String, Int]
//  def maxVersionForMarketDataSetNames(names:List[String]):Option[Int] //remove?
//
//  class Cursor(version:Int, mds:List[MarketDataSet]) {
//    //
//  }
//
//  def marketDataTypes(version:Int, mds:List[MarketDataSet]) : List[MarketDataType]
//  def queryForObservationDayAndMarketDataKeys(version:Int, mds:List[MarketDataSet], marketDataType: MarketDataType): List[TimedMarketDataKey]
//  def query(version:Int, mds:List[MarketDataSet], marketDataType: MarketDataType,
//            observationDays: Option[Set[Option[Day]]], observationTimes: Option[Set[ObservationTimeOfDay]],
//            marketDataKeys: Option[Set[MarketDataKey]]): List[(TimedMarketDataKey, MarketData)]
//
//  def latestMarketData(from: Day, to: Day, marketDataType: MarketDataType, marketDataSet: MarketDataSet): List[(TimedMarketDataKey, VersionedMarketData)]
//  def readLatest(id:MarketDataID):Option[VersionedMarketData]
//
//  def store(data: Iterable[MarketDataUpdate], marketDataSet: MarketDataSet):(Boolean,Int)
//
//}



class MarketDataTags(db: DBTrait[RichResultSetRow]) {

  def versionForSnapshot(snapshotID: SnapshotID) = {
    db.queryWithOneResult("select version from MarketDataTag where snapshotid = :id", Map("id" -> snapshotID.id)) {
      rs => rs.getInt("version")
    }.get
  }

  def latestSnapshot(pricingGroup: PricingGroup, observationDay: Day): Option[SnapshotID] = {
    db.queryWithOneResult(
      select("mdt.*")
        from("MarketDataTag mdt")
       where("marketDataSelection" eql PersistAsBlob(MarketDataSelection(Some(pricingGroup))))
         and("observationDay" eql observationDay)
     orderBy("snapshotTime" desc))
    { SnapshotID(_) }
  }

  def latestSnapshot(pricingGroup: PricingGroup): Option[SnapshotID] = {
    db.queryWithOneResult(
         select("mdt.*")
           from("MarketDataTag mdt")
          where("marketDataSelection" eql PersistAsBlob(MarketDataSelection(Some(pricingGroup))))
        orderBy("snapshotTime" desc))
    { SnapshotID(_) }
  }

  def snapshot(version: Int, marketDataSelection: MarketDataSelection, observationDay: Day) = {
    val optSnapshot = db.queryWithOneResult((select("*") from "MarketDataTag" where (("version" eql version)
      and ("marketDataSelection" eql LiteralString(StarlingXStream.write(marketDataSelection)))
      and ("observationDay" eql observationDay)))) {
      rs => SnapshotID(rs)
    }

    optSnapshot match {
      case Some(ss) => (ss, false)
      case None => {
        val timestamp = new Timestamp()
        val params = Map("snapshotTime" -> timestamp, "version" -> version, "marketDataSelection" -> StarlingXStream.write(marketDataSelection), "observationDay" -> observationDay)
        var id: Option[Long] = None
        db.inTransaction(writer => id = Some(writer.insertAndReturnKey("MarketDataTag", "snapshotid", params, Some(List("snapshotTime", "version", "marketDataSelection", "observationDay")))))
        val ss = SnapshotID(observationDay, id.get.toInt, timestamp, marketDataSelection, version)
        (ss, true)
      }

    }
  }

  def snapshots(): List[SnapshotID] = {
    db.queryWithResult("select * from MarketDataTag order by snapshotID desc", Map()) {
      rs => snapshotIDFromResultSetRow(rs)
    }
  }

  private def snapshotIDFromResultSetRow(rs: RichResultSetRow) = SnapshotID(rs)

  def snapshotsByMarketDataSelection(): MultiMap[MarketDataSelection, SnapshotIDLabel] = {
    snapshots().groupBy(_.marketDataSelection).map {
      case (selection, snapshots) => selection -> snapshots.map(_.label).sortWith(_ > _)
    }
  }

  def latestSnapshotFor(selection: MarketDataSelection): Option[SnapshotIDLabel] = {
    snapshots().filter(_.marketDataSelection == selection).optMaxBy(_.id).map(_.label)
  }

  def snapshotFromID(snapshotID: Int): Option[SnapshotID] = {
    db.queryWithOneResult("""
    select *
    from MarketDataTag
    where
      snapshotID = :snapshotID
    """, Map("snapshotID" -> snapshotID))(SnapshotID(_))
  }
}


object DBMarketDataStore {
  def apply(props: Props, db: DBTrait[RichResultSetRow], marketDataSources: MultiMap[MarketDataSet, MarketDataSource],
    broadcaster: Broadcaster = Broadcaster.Null, referenceDataLookup: ReferenceDataLookup = ReferenceDataLookup.Null): DBMarketDataStore = {

    val mddb = if (props.UseFasterMarketDataSchema()) new NewSchemaMdDB(db, referenceDataLookup) else new SlowMdDB(db)

    new DBMarketDataStore(mddb, new MarketDataTags(db), marketDataSources, broadcaster, referenceDataLookup)
  }
}

// TODO [12 May 2011] move me somewhere proper
class DBMarketDataStore(db: MdDB, tags: MarketDataTags, val marketDataSources: MultiMap[MarketDataSet, MarketDataSource],
  broadcaster: Broadcaster, referenceDataLookup: ReferenceDataLookup) extends MarketDataStore with Log {

  db.checkIntegrity()

  val importer = new MarketDataImporter(this)
  val pivotCache = CacheFactory.getCache("MarketDataStore.pivotCache")

  def readAll() {
    snapshotsByMarketDataSelection() //read MarketDataTag table
    db.readAll()
  }

  def sourcesFor(marketDataSet: MarketDataSet) = marketDataSources(marketDataSet)
  def sourcesFor(pricingGroup: PricingGroup) = marketDataSets(MarketDataSelection(Some(pricingGroup))).flatMap(sourcesFor(_))

  private lazy val excelDataSetsCache = new SynchronizedVar(db.marketDataSetNames().collect {
    case name if name.startsWith(MarketDataSet.excelPrefix) => name.stripPrefix(MarketDataSet.excelPrefix)
  }) {
    override def set(values: List[String]) { super.set(values.sortWith(_ < _)) }
  }

  private lazy val (observationDaysByPricingGroupCache, observationDaysByExcelCache) = {
    val observationDays = db.observationDaysByMarketDataSet

    val observationDaysByPricingGroupCache = pricingGroupsDefinitions.mapValues { marketDataSets =>
      new MSet() ++ marketDataSets.flatMap(marketDataSet => observationDays.getOrElse(marketDataSet.name, Nil))
    }.withDefaultValue(new MSet())

    val observationDaysByExcelCache =
      observationDays.filterKeys(_.startsWith(MarketDataSet.excelPrefix)).mapValues(new MSet() ++ _).withDefaultValue(new MSet())

    (observationDaysByPricingGroupCache, observationDaysByExcelCache)
  }

  def excelDataSets: List[String] = excelDataSetsCache.get

  def pricingGroupForName(name: String) = MarketDataStore.pricingGroupForName(name)

  def versionForSnapshot(snapshotID: SnapshotID) = {
    tags.versionForSnapshot(snapshotID)
  }

  def validate(reader: MarketDataReader): MarketDataReader = {
    new ValidatingMarketDataReader(reader, RollingAveragePriceValidator, new DayChangePriceValidator(reader))
  }

  def pivot(marketDataIdentifier: MarketDataIdentifier, marketDataType: MarketDataType, pivotEdits:PivotEdits=PivotEdits.Null): PivotTableDataSource = {
    //    val mds = marketDataSets(marketDataIdentifier.selection)
    //    val version = versionForMarketDataVersion(marketDataIdentifier.marketDataVersion)
    pivotCache.memoize((marketDataIdentifier, marketDataType, pivotEdits), {
      val reader = new NormalMarketDataReader(this, marketDataIdentifier)
      val validatingReader = validate(reader)

      new MarketDataPivotTableDataSource(validatingReader, pivotEdits, this, marketDataIdentifier, marketDataType,
        referenceDataLookup)
    })
  }

  def pivot(selection: MarketDataSelection, marketDataType: MarketDataType): PivotTableDataSource =
    pivot(latestMarketDataIdentifier(selection), marketDataType)

  def versionForMarketDataVersion(marketDataVersion: MarketDataVersion): Int = {
    marketDataVersion match {
      case SpecificMarketDataVersion(v) => v
      case SnapshotMarketDataVersion(ss) => snapshotFromID(ss.id).get.version
    }
  }

  def readLatest(marketDataSet: MarketDataSet, timedKey: TimedMarketDataKey): Option[VersionedMarketData] =
    readLatest(MarketDataID(timedKey.observationPoint, marketDataSet, timedKey.key))

  def readLatest[T <: MarketData](id: MarketDataID) = db.readLatest(id)

  def latestPricingGroupVersions: Map[PricingGroup, Int] = {
    val lookup = db.latestVersionForMarketDataSets()
    Map() ++ pricingGroupsDefinitions.mapValues {
      marketDataSets => marketDataSets.map(mds => lookup.getOrElse(mds, 0)).max
    }
  }

  def latestExcelVersions: Map[MarketDataSet, Int] = db.latestExcelVersions

  def latestMarketDataIdentifier(selection: MarketDataSelection) = MarketDataIdentifier(selection, latest(selection))

  def latest(selection: MarketDataSelection): Int = {
    val versions = latestPricingGroupVersions.get(selection.pricingGroup).toList :::
      latestExcelVersions.get(selection.excel.map(MarketDataSet.excel(_))).toList

    if (versions.isEmpty) 0 else versions.max
  }

  def latestSnapshot(pricingGroup: PricingGroup, observationDay: Day): Option[SnapshotID] = {
    tags.latestSnapshot(pricingGroup, observationDay)
  }

  def latestSnapshot(pricingGroup: PricingGroup) = tags.latestSnapshot(pricingGroup)

  def saveAll(marketDataSet: MarketDataSet, observationPoint: ObservationPoint, data: Map[MarketDataKey, MarketData]): SaveResult = {
    val dataX = data.map { case (marketDataKey, marketData) => MarketDataEntry(observationPoint, marketDataKey, marketData) }

    save(Map(marketDataSet -> dataX.toList))
  }

  def save(marketDataSetToData: MultiMap[MarketDataSet, MarketDataEntry]): SaveResult = {
    val setToUpdates = marketDataSetToData.map { case (marketDataSet, values) => {
      (marketDataSet, values.map(entry => entry.toUpdate(db.readLatest(entry.dataIdFor(marketDataSet)))))
    } }

    update(setToUpdates)
  }

  def update(marketDataSetToData: Map[MarketDataSet, Iterable[MarketDataUpdate]]): SaveResult = this.synchronized {
    val changedMarketDataSets = new scala.collection.mutable.HashMap[MarketDataSet, (Set[Day], Int)]()
    val allChangedDays = new ListBuffer[Day]

    var maxVersion = 0
    for ((marketDataSet, data) <- marketDataSetToData.toList.sortBy(_._1.name)) {
      maxVersion = scala.math.max(maxVersion, saveActions(data, marketDataSet, changedMarketDataSets))
    }

    for ((pricingGroup, marketDataSets) <- pricingGroupsDefinitions) {
      val changesForThisPricingGroup = changedMarketDataSets.filterKeys(marketDataSets)
      if (changesForThisPricingGroup.nonEmpty) {
        val maxVersion = changesForThisPricingGroup.values.maximum(_._2)
        broadcaster.broadcast(PricingGroupMarketDataUpdate(pricingGroup, maxVersion))

        val changedDays = changesForThisPricingGroup.values.map(_._1).foldRight(Set[Day]())(_ ++ _)
        val days = observationDaysByPricingGroupCache(pricingGroup)
        for (day <- changedDays if !days.contains(day)) {
          days += day
          broadcaster.broadcast(PricingGroupObservationDay(pricingGroup, day))
        }

        allChangedDays ++= changedDays
      }
    }

    SaveResult(maxVersion, changedMarketDataSets.nonEmpty, Some(allChangedDays.distinct.toList))
  }

  private def saveActions(data: Iterable[MarketDataUpdate], marketDataSet: MarketDataSet,
                          changedMarketDataSets: scala.collection.mutable.HashMap[MarketDataSet, (Set[Day], Int)]): Int = {

    val SaveResult(innerMaxVersion, update, _) = db.store(data, marketDataSet)
    if (update) {
      changedMarketDataSets(marketDataSet) = (data.flatMap(_.observationPoint.day.toList).toSet, innerMaxVersion)
    }

    MarketDataSet.fromExcel(marketDataSet).map { name =>
      if (!excelDataSetsCache.get.contains(name)) {
        excelDataSetsCache.update(name :: _)
        broadcaster.broadcast(ExcelMarketListUpdate(excelDataSetsCache.get))
      }
      // TODO [02 Jun 2011] Should this be getOrElseUpdate ? Stacy
      val days = observationDaysByExcelCache(name)

      data.flatMap(_.observationPoint.day.toList).filterNot(day => days.contains(day)).foreach(day => {
        days += day
        broadcaster.broadcast(ExcelObservationDay(name, day))
      })

      if (update) {
        broadcaster.broadcast(ExcelMarketDataUpdate(name, innerMaxVersion))
      }
    }

    innerMaxVersion
  }

  def save(marketDataSet: MarketDataSet, timedKey: TimedMarketDataKey, marketData: MarketData): Int = {
    save(Map(marketDataSet -> List(MarketDataEntry(timedKey.observationPoint, timedKey.key, marketData)))).maxVersion
  }

  def importData(marketDataSelection: MarketDataSelection, observationDay: Day) = {
    val marketDataSets = marketDataSelection.pricingGroup.flatMapL(pgl => pricingGroupsDefinitions(PricingGroup(pgl.name)))

    importFor(observationDay, marketDataSets: _*)
  }

  val importLock = new Object

  def importFor(observationDay: Day, marketDataSets: MarketDataSet*) = importLock.synchronized {
    log.infoWithTime("saving market data: " + observationDay) {
      val updates: MultiMap[MarketDataSet, MarketDataUpdate] = importer.getUpdates(observationDay, marketDataSets: _*)

      log.infoWithTime("Number of updates: " + updates.mapValues(_.toList.size)) {
        update(updates)
      }
    }
  }

  def latestSnapshotFor(marketDataSelection: MarketDataSelection): Option[SnapshotID] = {
    snapshots().filter(_.marketDataSelection == marketDataSelection).optMaxBy(_.id)
  }

  def snapshot(marketDataSelection: MarketDataSelection, doImport: Boolean, observationDay: Day): Option[SnapshotID] = {
    val previousSnapshot: Option[SnapshotIDLabel] = tags.latestSnapshotFor(marketDataSelection)

    val saveResult = importData(marketDataSelection, observationDay)

    getMaxVersion(marketDataSelection).map { version =>
      val (snapshotID, justCreated) = tags.snapshot(version, marketDataSelection, observationDay)

      println("snapshotid: " + snapshotID)

      if (justCreated) {
        broadcaster.broadcast(MarketDataSnapshotSet(marketDataSelection, previousSnapshot, snapshotID.label,
          saveResult.affectedObservationDays))
        broadcaster.broadcast(MarketDataSnapshot(List(snapshotID.id.toString)))
      }
      snapshotID
    }
  }

  private def getMaxVersion(marketDataSelection: MarketDataSelection): Option[Int] = {
    val names = marketDataSets(marketDataSelection).map(_.name)
    if (names.isEmpty) None else db.maxVersionForMarketDataSetNames(names)
  }

  def snapshotFromID(snapshotID: Int): Option[SnapshotID] = tags.snapshotFromID(snapshotID)
  def snapshots(): List[SnapshotID] = tags.snapshots()
  def snapshotsByMarketDataSelection(): MultiMap[MarketDataSelection, SnapshotIDLabel] = tags.snapshotsByMarketDataSelection()
  def observationDaysByPricingGroup(): Map[PricingGroup, Set[Day]] = observationDaysByPricingGroupCache.mapValues(_.toSet)
  def observationDaysByExcel(): Map[String, Set[Day]] = observationDaysByExcelCache.mapValues(_.toSet)

  def latestObservationDayForMarketDataSet(marketDataSet: MarketDataSet) = {
    var days = Set[Day]()
    observationDaysByPricingGroupCache.foreach {
      case (pg, obDays) if pricingGroupsDefinitions(pg) contains marketDataSet => {
        days ++= obDays
      }
      case _ =>
    }
    days.toList.sortWith(_ > _).headOption
  }

  def latestObservationDayFor(pricingGroup: PricingGroup, marketDataType: MarketDataType) = {
    db.latestObservationDaysFor(pricingGroupsDefinitions(pricingGroup), marketDataType)
      .getOrThrow("No observation days for: " + marketDataType)
  }

  private def marketDataSets(marketDataIdentifier: MarketDataIdentifier): List[MarketDataSet] = marketDataSets(marketDataIdentifier.selection)

  def marketDataSets(marketDataSelection: MarketDataSelection): List[MarketDataSet] = {
    val pgmds = marketDataSelection.pricingGroup.flatMapL(pgl => pricingGroupsDefinitions(PricingGroup(pgl.name)))
    val excelmds = marketDataSelection.excel.toList.map(MarketDataSet.excel(_))

    excelmds ::: pgmds
  }

  def marketDataTypes(marketDataIdentifier: MarketDataIdentifier): List[MarketDataType] = {
    val version = versionForMarketDataVersion(marketDataIdentifier.marketDataVersion)
    val mds = marketDataSets(marketDataIdentifier)
    db.marketDataTypes(version, mds).toList
  }

  def marketData(from: Day, to: Day, marketDataType: MarketDataType, marketDataSet: MarketDataSet) = {
    db.latestMarketData(from, to, marketDataType, marketDataSet)
  }

  def queryForObservationDayAndMarketDataKeys(marketDataIdentifier: MarketDataIdentifier, marketDataType: MarketDataType): List[TimedMarketDataKey] = {
    val version = versionForMarketDataVersion(marketDataIdentifier.marketDataVersion)
    val mds = marketDataSets(marketDataIdentifier)
    db.queryForObservationDayAndMarketDataKeys(version, mds, marketDataType).toList
  }

  def query(marketDataIdentifier: MarketDataIdentifier, marketDataType: MarketDataType,
            observationDays: Option[Set[Option[Day]]] = None, observationTimes: Option[Set[ObservationTimeOfDay]] = None,
            marketDataKeys: Option[Set[MarketDataKey]] = None): List[(TimedMarketDataKey, MarketData)] = {
    val version = versionForMarketDataVersion(marketDataIdentifier.marketDataVersion)
    val mds = marketDataSets(marketDataIdentifier)
    db.query(version, mds, marketDataType, observationDays, observationTimes, marketDataKeys)
  }

  private var timings = 0.0

  private def timer[T](block: => T) = {
    val stopwatch = new Stopwatch
    val return_value: T = block
    timings += stopwatch.ms / 1000.0
    //Log.info(tableName + ": cumulative time to date: " + timings)
    return_value
  }
}