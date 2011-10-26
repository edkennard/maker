package starling.metals

import swing.event.Event

import starling.curves._
import starling.databases.utils.{RabbitMessageSender, RabbitBroadcaster}
import starling.daterange.TimeOfDay
import starling.manager._
import starling.marketdata.{MarketDataTypes, ReferenceDataLookup}
import starling.services._
import starling.services.jmx.StarlingJMX
import starling.services.rabbit.{TitanRabbitIdBroadcaster, MockTitanRabbitEventServices, DefaultTitanRabbitEventServices}
import starling.services.rpc.logistics.{FileMockedTitanLogisticsServices, DefaultTitanLogisticsServices}
import starling.services.rpc.valuation._
import starling.services.trinity.{XRTGenerator, FCLGenerator, TrinityUploader}
import starling.titan.{TitanTradeStoreManager, TitanSystemOfRecord, TitanTradeStore}
import starling.tradestore.TradeStores
import starling.utils._
import starling.utils.ImplicitConversions._
import starling.scheduler.ScheduledTime._
import com.trafigura.services.trinity.TrinityService
import com.trafigura.services.ResteasyServiceApi
import starling.gui.api.{MarketDataSelection, PricingGroup}
import org.joda.time.Period
import starling.calendar.BusinessCalendars
import starling.scheduler.{TaskDescription, Scheduler}
import starling.db.{DB, TitanTradeSystem, MarketDataStore}


class MetalsBromptonActivator extends BromptonActivator with Log with scalaz.Identitys {

  def start(context: BromptonContext) {

    val props = context.awaitService(classOf[starling.props.Props])
    val startRabbit = true
    val testMode = false

    val marketDataStore = context.awaitService(classOf[MarketDataStore])
    val referenceDataLookup = context.awaitService(classOf[ReferenceDataLookup])

    val titanTradeStore = {
      val tradeStores = context.awaitService(classOf[TradeStores])

      tradeStores.titanTradeStore.asInstanceOf[TitanTradeStore]
    }

    val titanRabbitEventServices = if (!testMode) {
      new DefaultTitanRabbitEventServices(props)
    }
    else {
      new MockTitanRabbitEventServices()
    }

    if (props.RabbitEnabled()) {
      titanRabbitEventServices.start
    }

    val osgiBroadcaster = context.awaitService(classOf[Broadcaster])

    val broadcaster = {
      val emailService = new PersistingEmailService(
        osgiBroadcaster, DB(props.StarlingDatabase()), props.SmtpServerHost(), props.SmtpServerPort())

      context.registerService(classOf[EmailService], emailService, ServiceProperties(ExportGuiRMIProperty))

      new CompositeBroadcaster(
        props.rabbitHostSet                       → new RabbitBroadcaster(new RabbitMessageSender(props.RabbitHost())),
        props.EnableVerificationEmails()          → new EmailBroadcaster(emailService, props),
        (startRabbit && props.titanRabbitHostSet) → TitanRabbitIdBroadcaster(titanRabbitEventServices.rabbitEventPublisher)
      )
    }

    context.registerService(classOf[Receiver], new Receiver() {
      def event(event: Event) = broadcaster.broadcast(event)
    })

    val environmentProvider = new DefaultEnvironmentProvider(marketDataStore, referenceDataLookup)

    val titanTradeStoreManager: TitanTradeStoreManager = {
      import starling.services.rpc.refdata.{FileMockedTitanServices, DefaultTitanServices}

      val (titanServices, logisticsServices) = if (!testMode) (
        new DefaultTitanServices(props),
        new DefaultTitanLogisticsServices(props)
      )
      else (
        new FileMockedTitanServices(),
        new FileMockedTitanLogisticsServices()
      )

      val manager = TitanTradeStoreManager(titanServices, titanTradeStore, titanServices, logisticsServices)

      context.onStarted { manager.start }

      manager
    }

    val valuationSnapshotCreator = new MetalValuationSnapshotCreator(osgiBroadcaster, environmentProvider, titanTradeStore)
    context.registerService(classOf[Receiver], valuationSnapshotCreator)

    {
      import starling.rmi.{RabbitEventDatabase, DefaultRabbitEventDatabase}

      val starlingDB = DB(props.StarlingDatabase( ))
      val rabbitEventDatabase = new DefaultRabbitEventDatabase(starlingDB, osgiBroadcaster)

      titanRabbitEventServices.addClient(new TitanEventHandler(osgiBroadcaster, titanTradeStoreManager,
        environmentProvider, rabbitEventDatabase))

      context.registerService(classOf[RabbitEventDatabase], rabbitEventDatabase)
    }

    {
      import starling.tradeimport.TradeImporter

      val tradeImporter = new TradeImporter(TitanTradeSystem, new TitanSystemOfRecord(titanTradeStoreManager), titanTradeStore)

      context.registerService(classOf[TradeImporter], tradeImporter)
    }

    registerScheduler(context, broadcaster)

    context.registerService(classOf[ReferenceData], new ReferenceData("Bloomberg → LIM", new BloombergImportsReferenceData(DB(props.EAIReplica()))))

    {
      import com.trafigura.services.valuation.ValuationServiceApi

      val valuationService = new ValuationService(environmentProvider, titanTradeStore)

      context.registerService(classOf[ValuationServiceApi], valuationService, ServiceProperties(ExportTitanRMIProperty, ExportTitanHTTPProperty))
    }

    {
      import com.trafigura.services.marketdata._
      import starling.services.rpc.marketdata._

      val marketDataService = new MarketDataService(marketDataStore, environmentProvider)

      context.registerService(classOf[MarketDataServiceApi], marketDataService, ServiceProperties(ExportTitanRMIProperty, ExportTitanHTTPProperty))
      context.registerService(classOf[ExampleServiceApi], ExampleService, ServiceProperties(ExportTitanHTTPProperty))
    }

    {
      import starling.daterange.ObservationTimeOfDay._
      import starling.gui.api.{PricingGroup, EnvironmentRuleLabel}

      val dataTypes = new MarketDataTypes(referenceDataLookup)

      val metalRules = List(
        ClosesEnvironmentRule(referenceDataLookup),
        ClosesEnvironmentRule(referenceDataLookup, allowOldPricesToBeUsed = true),
        new VanillaEnvironmentRule(_.atTimeOfDay(SHFEClose), TimeOfDay.EndOfDay, new EnvironmentRuleLabel(SHFEClose.name),
          List(PricingGroup.Metals), referenceDataLookup, dataTypes),
        new TimeShiftToLMECloseEnvironmentRule(referenceDataLookup, dataTypes)
      )

      metalRules.foreach(context.registerService(classOf[EnvironmentRule], _))
    }
  }

  // TODO [19 Oct 2011] Reuse scheduler for oil tasks
  private def registerScheduler(context: BromptonContext, broadcaster: Broadcaster) {
    val props = context.awaitService(classOf[starling.props.Props])
    val scheduler = new Scheduler(props)
    val jmx = new StarlingJMX(scheduler)

    context.onStarted { scheduler.start; jmx.start }
    context.registerService(classOf[ReferenceData], new ReferenceData("Schedules", new SchedulerReferenceData(scheduler)))
    context.createServiceTracker(Some(classOf[TaskDescription]), serviceTracker = new BromptonServiceCallback[TaskDescription] {
      def serviceAdded(ref: BromptonServiceReference, properties: ServiceProperties, task: TaskDescription) = {
        scheduler += task
      }
    })

    if (props.ServerType() == "FC2" && props.ImportMarketDataAutomatically()) registerFC2Tasks(context, broadcaster)
  }

  private def registerFC2Tasks(context: BromptonContext, broadcaster: Broadcaster) {
    val marketDataStore = context.awaitService(classOf[MarketDataStore])
    val businessCalendars = context.awaitService(classOf[BusinessCalendars])

    context.registerService(classOf[TaskDescription], importLim(businessCalendars, marketDataStore))
    context.registerService(classOf[TaskDescription], copyBenchmarksAndFreightParity(businessCalendars, marketDataStore))

    registerDataValidationTasks(context, broadcaster)
    registerTrinityTask(context)
  }

  private def importLim(businessCalendars: BusinessCalendars, marketDataStore: MarketDataStore) = TaskDescription("Import LIM",
    everyFiveMinutes(businessCalendars.LME), new ImportMarketDataTask(marketDataStore, PricingGroup.Metals)
      .withSource("LIM", marketDataStore.sourcesFor(PricingGroup.Metals).flatMap(_.description): _*))

  private def copyBenchmarksAndFreightParity(businessCalendars: BusinessCalendars, marketDataStore: MarketDataStore) =
    TaskDescription("Copy Freight Parity & Benchmarks", hourly(businessCalendars.weekDay()), CopyManualData(marketDataStore))

  private def registerDataValidationTasks(context: BromptonContext, broadcaster: Broadcaster) {
    val marketDataStore = context.awaitService(classOf[MarketDataStore])

    val marketDataSources = marketDataStore.sourcesFor(PricingGroup.Metals)

    val marketDataAvailabilityBroadcaster = new MarketDataAvailabilityBroadcaster(marketDataStore, broadcaster,
      marketDataSources.flatMap(_.eventSources(marketDataStore)))

    marketDataSources.flatMap(_.availabilityTasks(marketDataStore)).map(_.offset(Period.minutes(30)))
      .foreach(context.registerService(classOf[TaskDescription], _))

    context.registerService(classOf[Receiver], marketDataAvailabilityBroadcaster)
  }

  private def registerTrinityTask(context: BromptonContext) {
    val props = context.awaitService(classOf[starling.props.Props])
    val marketDataStore = context.awaitService(classOf[MarketDataStore])
    val businessCalendars = context.awaitService(classOf[BusinessCalendars])

    val curveViewer = context.awaitService(classOf[CurveViewer])
    val trinityService = new TrinityService(ResteasyServiceApi(props.TrinityServiceUrl()))
    val trinityUploader = new TrinityUploader(new FCLGenerator(businessCalendars, curveViewer),
          new XRTGenerator(marketDataStore), trinityService, props)

    val task = TaskDescription("Upload Curves to Trinity", daily(businessCalendars.LME, 19 H 00),
      new UploadCurveToTrinityTask(trinityUploader,
        marketDataStore.latestMarketDataIdentifier(MarketDataSelection(Some(PricingGroup.Metals)))))

    context.registerService(classOf[TaskDescription], task)

    //val uploadLibor = SimpleScheduledTask(trinityUploader.uploadLibor).withSink("Trinity")
    //TaskDescription("Upload Libor to Trinity", daily(businessCalendars.LME, 23 H 45), uploadLibor)
  }
}