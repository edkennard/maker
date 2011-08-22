package starling.gui

import api._
import javax.swing._
import pages._
import scala.swing.event.WindowClosing
import scala.swing.event.MouseClicked
import scala.swing.event.ButtonClicked
import scala.swing.event.Event
import starling.gui.StandardUserSettingKeys._
import scala.swing._
import scala.swing.{ButtonGroup => ScalaButtonGroup}
import scala.swing.{AbstractButton=> ScalaAbstractButton}
import java.awt.event._
import scala.swing.Swing._
import starling.rmi.{StarlingServer}
import collection.mutable.ListBuffer
import starling.bouncyrmi.{BouncyRMIClient}
import javax.security.auth.login.LoginException
import starling.utils.{StackTraceToString, Log}
import starling.gui.LocalCacheKeys._
import starling.daterange.Day
import starling.auth.{Client, ClientLogin}
import management.ManagementFactory
import xstream.GuiStarlingXStream
import starling.browser._
import common._
import starling.browser.service.internal.HeterogeneousMap
import service._
import java.awt.{Cursor, GraphicsEnvironment, Color, KeyboardFocusManager}
import starling.browser.internal.{NotificationKeys, NotificationType, Notification}
import starling.pivot._
import javax.swing.event.{ChangeEvent, ChangeListener}
import GuiUtils._
import starling.pivot.utils.PeriodPivotFormatter

object StarlingServerNotificationHandlers {
  def notificationHandler = {
    import starling.gui.StarlingLocalCache._
    new NotificationHook {
      def handle(event:Event, cache:LocalCache, sendNotification:(Notification) => Unit) {
        event match {
          case DeskClosed(desk, timestamp) => {
            if(cache.desks.contains(desk)) {
              val old: Map[Desk, Map[Day, List[TradeTimestamp]]] = cache.localCache(DeskCloses)
              val oldCloses: Map[Day, List[TradeTimestamp]] = old.getOrElse(desk, Map())
              val newCloses: Map[Day, List[TradeTimestamp]] = oldCloses + (timestamp.closeDay -> (timestamp :: oldCloses.getOrElse(timestamp.closeDay, Nil)))
              val newMap = old + (desk -> newCloses)
              cache.localCache(DeskCloses) = newMap
              val text = "Imported book close for " + desk + " (" + timestamp.timestamp.toStringMinutes + ")"
              val notification =  Notification(text, StarlingIcons.icon("/icons/16x16_book.png"), NotificationType.Message, {})
              sendNotification(notification)
            }
          }
          case DeskCloseFailed(desk, timestamp, error) => {
            if(cache.desks.contains(desk)) {
              val old: Map[Desk, Map[Day, List[TradeTimestamp]]] = cache.localCache(DeskCloses)
              val oldCloses: Map[Day, List[TradeTimestamp]] = old.getOrElse(desk, Map())
              val newCloses: Map[Day, List[TradeTimestamp]] = oldCloses + (timestamp.closeDay -> (timestamp :: oldCloses.getOrElse(timestamp.closeDay, Nil)))
              val newMap = old + (desk -> newCloses)
              cache.localCache(DeskCloses) = newMap
              val text = "Import failed for " + desk + " (" + timestamp.timestamp.toStringMinutes + ")"
              val notification = Notification(text, StarlingIcons.icon("/icons/16x16_error.png"), NotificationType.Message, {})
              sendNotification(notification)
            }
          }
          case _ =>
        }
      }
    }

  }

}
/**
 * The entry point into the starling gui
 */
object Launcher extends Log {
  def main(args: Array[String]) {
    if (args.length != 3) {
      throw new IllegalArgumentException("You need to specify 3 arguments: hostname, rmi port and servicePrincipalName")
    }
    println(List() ++ args)
    val rmiHost = args(0)
    val rmiPort = args(1).toInt
    val servicePrincipalName = args(2)
    start(rmiHost, rmiPort, servicePrincipalName)
  }

  def systemInfo = {
    import starling.utils.ImplicitConversions._

    val screenDevices = GraphicsEnvironment.getLocalGraphicsEnvironment.getScreenDevices.toList
    val monitors = screenDevices.map(sd => {
      val dm = sd.getDisplayMode

      MonitorInfo(
        sd.toString,
        dm.getWidth,
        dm.getHeight,
        sd.getAvailableAcceleratedMemory.megs
      )
    })

    val system = ManagementFactory.getOperatingSystemMXBean
    val vm = ManagementFactory.getRuntimeMXBean

    val (totalMem, freeMem) = system match {
      case sun:com.sun.management.OperatingSystemMXBean => (sun.getTotalPhysicalMemorySize.megs.toInt, sun.getFreePhysicalMemorySize.megs.toInt)
      case _ => (-1,-1)
    }

    OSInfo(
      system.getName,
      system.getArch,
      system.getVersion,
      vm.getVmName,
      vm.getVmVendor,
      System.getProperty("java.runtime.version"),
      system.getAvailableProcessors,
      totalMem,
      freeMem,
      monitors.size,
      monitors
    )
  }

  // These variables are a big hack so we remember what they are when running the start method when changing users.
  var rmiHost = ""
  var rmiPort = -1
  var servicePrincipalName = ""

  def start(rmiHost: String, rmiPort: Int, servicePrincipalName: String, overriddenUser:Option[String] = None) {
    this.rmiHost = rmiHost
    this.rmiPort = rmiPort
    this.servicePrincipalName = servicePrincipalName

    def logger(message:String) {
      // TODO [03 Feb 2011] do something clever with this message.
//      println(message)
    }
    val client = new BouncyRMIClient(rmiHost, rmiPort, classOf[StarlingServer], auth(servicePrincipalName), logger, overriddenUser)
    try {
      client.startBlocking
      val starlingServer = client.proxy
      val extraInfo = overriddenUser match {
        case None => {
          // When the user isn't overridden, store the system info on each log on.
          starlingServer.storeSystemInfo(systemInfo)
          None
        }
        case Some(_) => Some("You are " + starlingServer.whoAmI.name) // Want to see who I actually am, not who I tried to be.
      }
      start(starlingServer, client.remotePublisher, extraInfo)
    }
    catch {
      case t => showErrorThenExit(t)
    }
  }

  def start(starlingServer:StarlingServer, remotePublisher: Publisher, extraInfo:Option[String]) {
    val postLocalCacheUpdatePublisher = new scala.swing.Publisher() {}

    BrowserLauncher.start(
        StarlingServerNotificationHandlers.notificationHandler :: Nil,
        postLocalCacheUpdatePublisher,
        createCacheMap(starlingServer.whoAmI.name, starlingServer, postLocalCacheUpdatePublisher, remotePublisher),
        extraInfo) {
      new ServerContext {

        def username = starlingServer.whoAmI.name
        def version = starlingServer.version

        def lookup[T](klass:Class[T]) = {
          if (klass == classOf[StarlingServer]) starlingServer.asInstanceOf[T] else throw new Exception("Don't know how to handle " + klass)
        }

        def browserBundles = List(browserContext)

        import StarlingLocalCache._
        val browserContext = new BrowserBundle() {
          def bundleName = "StarlingServer"
          def marshal(obj: AnyRef) = GuiStarlingXStream.write(obj)
          override def userPage(context:PageContext) = Some( UserDetailsPage(context.localCache.currentUser) )
          override def hotKeys = HotKey(
            KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
            "utilsPage",
            UtilsPage()) :: Nil

          def unmarshal(text: String) = GuiStarlingXStream.read(text).asInstanceOf[AnyRef]

//            bookmark match {
//              case rb:ReportBookmark => {
//                val userReportData = rb.userReportData
//                val pivotLayout = rb.pivotPageState.pivotFieldParams.pivotFieldState match {
//                  case None => throw new Exception("I should have a layout at this stage")
//                  case Some(pfs) => PivotLayout(name, pfs, true, rb.pivotPageState.otherLayoutInfo, "special", Nil)
//                }
//                starlingServer.saveUserReport(name, userReportData, showParameters)
//                if (shouldSaveLayout && shouldAssociateLayout) {
//                  // This is the case where it is a custom layout so we want to save the layout and associate it with this report
//                  starlingServer.saveLayout(pivotLayout.copy(associatedReports = List(name)))
//                } else if (shouldAssociateLayout) {
//                  // This is the case where the layout is already saved but we want to associate it with this report.
//                  starlingServer.deleteLayout(pivotLayout.layoutName)
//                  starlingServer.saveLayout(pivotLayout.copy(associatedReports = name :: pivotLayout.associatedReports))
//                }
//              }
//            }

          override def settings(pageContext:PageContext) = StarlingSettings.create(pageContext)
          override def homeButtons(pageContext:PageContext) = StarlingHomeButtons.create(pageContext)
        }

        def browserService = new BrowserService {

          def name = starlingServer.name

          def logPageView(info: PageLogInfo) { starlingServer.logPageView(info) }

          def bookmarks = starlingServer.bookmarks
          def saveBookmark(bookmark:BookmarkLabel) { starlingServer.saveBookmark(bookmark) }
          def deleteBookmark(name:String) {starlingServer.deleteBookmark(name)}

          def saveSettings(settings:UserSettingsLabel) = starlingServer.saveSettings(settings)
          def readSettings = starlingServer.readSettings
        }
      }
    }

  }

  def createCacheMap(
                      username:String,
                      starlingServer:StarlingServer,
                      postLocalCacheUpdatePublisher : Publisher,
                      remotePublisher: Publisher):HeterogeneousMap[LocalCacheKey] = {
    val localCacheUpdatePublisher = new scala.swing.Publisher() {}
    remotePublisher.reactions += {
      case batch:EventBatch => {
        onEDT {
          //This indirection between the remotePublisher ensures that pages see
          // the events after the local cache is updated
          // and also ensures that the pages receive the event on the EDT
          batch.events.foreach { e => localCacheUpdatePublisher.publish(e) }
          postLocalCacheUpdatePublisher.publish(batch)
        }
      }
    }

    val cacheMap = new HeterogeneousMap[LocalCacheKey]
    localCacheUpdatePublisher.reactions += {
      case e: PivotLayoutUpdate if (e.user == username) => {
        cacheMap(UserPivotLayouts) = e.userLayouts
      }
      case ExcelMarketListUpdate(values) => {
        cacheMap(ExcelDataSets) = values
      }
      case mdss: MarketDataSnapshotSet => {
        val snapshotsBySelection = cacheMap(Snapshots)

        val snapshots = snapshotsBySelection.get(mdss.selection)

        val newLabels = snapshots match {
          case None => List(mdss.newSnapshot)
          case Some(labels) => mdss.newSnapshot :: labels
        }

        cacheMap(Snapshots) = snapshotsBySelection.updated(mdss.selection, newLabels)
      }
      case PricingGroupMarketDataUpdate(pg, version) => {
        cacheMap(PricingGroupLatestMarketDataVersion) =
                cacheMap(PricingGroupLatestMarketDataVersion) + (pg -> version)
      }
      case ExcelMarketDataUpdate(excel, version) => {
        cacheMap(ExcelLatestMarketDataVersion) =
                cacheMap(ExcelLatestMarketDataVersion) + (excel -> version)
      }
      case IntradayUpdated(group, user, timestamp) => {
        cacheMap(IntradayLatest) = cacheMap(IntradayLatest) + (group -> (user, timestamp))
      }
      case ExcelObservationDay(name, day) => {
        val current = cacheMap(ObservationDaysForExcel)
        cacheMap(ObservationDaysForExcel) = {
          current.updated(name, current.getOrElse(name, Set()) + day)
        }
      }
      case PricingGroupObservationDay(pricingGroup, day) => {
        val current = cacheMap(ObservationDaysForPricingGroup)
        cacheMap(ObservationDaysForPricingGroup) = {
          current.updated(pricingGroup, current.getOrElse(pricingGroup, Set()) + day)
        }
      }
      case UserLoggedIn(username) => {
        val current = cacheMap(LocalCache.AllUserNames)
        if (!current.exists(_ == username)) {
          cacheMap(LocalCache.AllUserNames) = username :: current
        }
      }
    }

    import LocalCache._
    import NotificationKeys._
    try {
      cacheMap(AllUserNames) = starlingServer.allUserNames
      cacheMap(UserPivotLayouts) = starlingServer.extraLayouts
      cacheMap(PricingGroups) = starlingServer.pricingGroups
      cacheMap(ExcelDataSets) = starlingServer.excelDataSets
      cacheMap(Snapshots) = starlingServer.snapshots
      val (observationDaysForPricingGroup, observationDaysForExcel) = starlingServer.observationDays
      cacheMap(ObservationDaysForPricingGroup) = observationDaysForPricingGroup
      cacheMap(ObservationDaysForExcel) = observationDaysForExcel
      cacheMap(ExcelLatestMarketDataVersion) = starlingServer.excelLatestMarketDataVersions
      cacheMap(PricingGroupLatestMarketDataVersion) = starlingServer.pricingGroupLatestMarketDataVersions
      cacheMap(LocalCacheKeys.ReportOptionsAvailable) = starlingServer.reportOptionsAvailable
      cacheMap(DeskCloses) = starlingServer.deskCloses
      cacheMap(IntradayLatest) = starlingServer.intradayLatest
      cacheMap(TradersBookLookup) = starlingServer.traders
      cacheMap(CurrentUser) = starlingServer.whoAmI
      cacheMap(UKBusinessCalendar) = starlingServer.ukBusinessCalendar
      cacheMap(Desks) = starlingServer.desks
      cacheMap(GroupToDesksMap) = starlingServer.groupToDesksMap
      cacheMap(IsStarlingDeveloper) = starlingServer.isStarlingDeveloper
      cacheMap(EnvironmentRules) = starlingServer.environmentRules
      cacheMap(CurveTypes) = starlingServer.curveTypes
//      cacheMap(Bookmarks) = toBookmarks(starlingServer.bookmarks)

    } catch {
      case e : Throwable =>
        e.printStackTrace()
        throw e
    }
    cacheMap
  }


  private def getMessage(t: Throwable): String = {
    val m = if (t.getMessage == null) {
      "Error"
    } else {
      t.getMessage
    }

    if (m.length > 60) {
      m.substring(0, 60) + " ... "
    } else {
      m
    }

    m
  }

  def showErrorThenExit(t: Throwable) {
    log.fatal("Failed to start starling: ", t)
    onEDT {
      GuiUtils.setLookAndFeel
      val f = new Frame {
        title = "Could not start Starling"
        iconImage = StarlingIcons.icon("/icons/32x32/status/weather-few-clouds.png").getImage
        val okButton = new Button("Ok") {reactions += {case ButtonClicked(e) => exit}}
        contents = new MigPanel("insets n 0 n n") {
          val image = StarlingIcons.im("/icons/128x128_storm_dead_bird.png")
          val imagePanel = new FixedImagePanel(image)

          val message = getMessage(t)

          val l = new Label("Failed to start Starling: " + message)

          val textArea = new TextArea(StackTraceToString.string(t)) {
            editable = false
          }
          val scrollPane = new ScrollPane(textArea) {
            preferredSize = new Dimension(500, 200)
          }

          add(imagePanel, "ay top")
          add(l, "ay top, gaptop 40lp, split, flowy")
          add(scrollPane, "gaptop 30lp, wrap unrel, push, grow")
          add(okButton, "split, spanx, tag ok")
        }
        pack
        centerOnScreen
        defaultButton = okButton
        visible = true

        reactions += {
          case WindowClosing(w) => exit
        }

        def exit = System.exit(-1)
      }
    }
  }

  def auth(servicePrincipalName: String): Client = {
    try {
      val subject = new ClientLogin().login
      new Client(servicePrincipalName, subject)
    } catch {
      case l: LoginException => {
        import starling.utils.Utils._
        os match {
          case Linux => {
            log.error("Failed to initialise kerberos, either it isn't used on this system or the ticket cache is stale (try krenew). Skipping kerberos.")
            new Client(null, null) {
              override def ticket = null
            }
          }
          case _: Windows => {
            throw new Exception("Windows: Failed to initialise kerberos for Starling log in.", l)
          }
          case u: UnknownOS => {
            throw new Exception(u + ": Failed to initialise kerberos for Starling log in.", l)
          }
        }
      }
    }
  }
}

object StarlingHomeButtons {
  def create(context:PageContext) = {
    import StarlingLocalCache._

    def gotoTradePage(ctrlDown:Boolean) {
      context.goTo( {
        val initial = {
          val defaultSelection = (context.localCache.desks.headOption, None)
          val lastSelection = context.getSetting(StandardUserSettingKeys.InitialTradeSelection, defaultSelection)
          lastSelection match {
            case (_, Some(groups)) => {
              val validGroups = context.localCache.intradaySubgroups.keySet
              if (groups.subgroups.forall(g => validGroups.exists(vg => vg.startsWith(g)))) lastSelection else defaultSelection
            }
            case _ => lastSelection
          }
        }

        val deskWithTime = initial._1.flatMap(d => context.localCache.latestTimestamp(d).map(ts => (d, ts)))
        val intradayWithTime = initial._2.map(groups => (groups, context.localCache.latestTimestamp(groups)))

        TradeSelectionPage(TradePageParameters(
          deskWithTime, intradayWithTime,
          TradeExpiryDay(Day.today())), PivotPageState(false, PivotFieldParams(true, None)))
      }, ctrlDown)
    }

    def gotoMarketDataPage(ctrlDown:Boolean) {
      MarketDataPage.goTo(context, StandardMarketDataPageIdentifier(defaultMarketDataIdentifier), None, None, ctrlDown)
    }

    def gotoCurvePage(ctrlDown: Boolean) {
      val curveLabel = CurveLabel(CurveTypeLabel("Price"), defaultMarketDataIdentifier, EnvironmentSpecificationLabel(
        context.localCache.populatedDays(defaultMarketDataIdentifier.selection).lastOption.getOrElse(Day.today()),
        context.localCache.environmentRulesForPricingGroup(defaultMarketDataIdentifier.selection.pricingGroup).head
      ))

      val initialState = PivotFieldsState(
        dataFields = List(Field("Price"), Field("Input")),
        columnFields = List(Field("Observation Time"), Field("Market")),
        rowFields = List(Field("Period"))
      )

      context.goTo(new CurvePage(curveLabel, PivotPageState.default(initialState)), ctrlDown)
    }

    def defaultMarketDataIdentifier: MarketDataIdentifier = {
      val initialSelection = context.getSetting(StandardUserSettingKeys.InitialMarketDataSelection,
        MarketDataSelection(context.localCache.pricingGroups(None).headOption))
      val latestMarketDataVersion = context.localCache.latestMarketDataVersion(initialSelection)

      MarketDataIdentifier(initialSelection, latestMarketDataVersion)
    }

    val tradeDataImage = StarlingIcons.im("/icons/32x32_trades.png")
    val tradeDataButton = new NumberedButton("Trades", tradeDataImage, gotoTradePage, number = Some("1."))

    val refDataImage = StarlingIcons.im("/icons/32x32_ref_data.png")
    val refDataButton = new NumberedButton("Reference Data", refDataImage, ctrlDown => {context.goTo(ReferenceDataIndexPage, ctrlDown)}, number = Some("2."))

    val marketDataImage = StarlingIcons.im("/icons/32x32_market_data.png")
    val marketDataButton = new NumberedButton("Market Data", marketDataImage, gotoMarketDataPage, number = Some("3."))

    val curveViewerImage = StarlingIcons.im("/icons/32x32_curve_viewer.png")
    val curveViewerButton = new NumberedButton("Curve Viewer", curveViewerImage, gotoCurvePage, number = Some("4."))

    tradeDataButton :: refDataButton :: marketDataButton :: curveViewerButton :: Nil
  }
}


object StarlingSettings {

  def create(context:PageContext) = {

    val currentSettings = context.getSetting(ExtraFormattingInfo, PivotFormatter.DefaultExtraFormatInfo)
    val dp = currentSettings.decimalPlaces
    def saveSettings() {
      context.putSetting(ExtraFormattingInfo, ExtraFormatInfo(decimalPlacesPanel.decimalPlaces, dateRangeFormatPanel.dateRangeFormat))
    }
    lazy val decimalPlacesPanel = new MigPanel("insets n n n 0", "[" + StandardLeftIndent + "][p]") {
      def createSpinner(initialValue:Int) = {
        val maxDP = 10
        val spinnerModel = new SpinnerNumberModel(initialValue, 0, maxDP, 1) {
          addChangeListener(new ChangeListener {
            def stateChanged(e:ChangeEvent) {
              saveSettings()
            }
          })
        }
        new JSpinner(spinnerModel) {
          def format = {
            val num = getValue.asInstanceOf[Int]
            if (num > 0) {
              "#,##0." + List.fill(num)("0").mkString
            } else {
              "#,##0"
            }
          }
        }
      }

      def numFromText(t:String) = {
        val lastIndex = t.lastIndexOf(".")
        if (lastIndex == -1) {
          0
        } else {
          t.length - 1 - lastIndex
        }
      }

      val defaultSpinner = createSpinner(numFromText(dp.defaultFormat))
      val priceSpinner = createSpinner(numFromText(dp.priceFormat))
      val currencySpinner = createSpinner(numFromText(dp.currencyFormat))
      val lotsSpinner = createSpinner(numFromText(dp.lotsFormat))
      val percentSpinner = createSpinner(numFromText(dp.percentageFormat))

      add(LabelWithSeparator("Decimal Places"), "spanx, growx, wrap")
      add(new Label("Default:"), "skip 1")
      add(defaultSpinner, "wrap")
      add(new Label("Price:"), "skip 1")
      add(priceSpinner, "wrap")
      add(new Label("Currency:"), "skip 1")
      add(currencySpinner, "wrap")
      add(new Label("Lots:"), "skip 1")
      add(lotsSpinner, "wrap")
      add(new Label("Percent:"), "skip 1")
      add(percentSpinner)

      def decimalPlaces = DecimalPlaces(defaultSpinner.format, lotsSpinner.format, priceSpinner.format, currencySpinner.format, percentSpinner.format)
      def decimalPlaces_=(dp:DecimalPlaces) {
        defaultSpinner.setValue(numFromText(dp.defaultFormat))
        priceSpinner.setValue(numFromText(dp.priceFormat))
        currencySpinner.setValue(numFromText(dp.currencyFormat))
        lotsSpinner.setValue(numFromText(dp.lotsFormat))
        percentSpinner.setValue(numFromText(dp.percentageFormat))
      }
    }
    lazy val dateRangeFormatPanel = new MigPanel("insets n n n 0", "[" + StandardLeftIndent + "][p]") {
      import MonthFormat._
      val standardExtraInfo = ExtraFormatInfo(dateRangeFormat = DateRangeFormat(Standard))
      val shortExtraInfo = ExtraFormatInfo(dateRangeFormat = DateRangeFormat(Short))
      val numericExtraInfo = ExtraFormatInfo(dateRangeFormat = DateRangeFormat(Numeric))
      val reutersExtraInfo = ExtraFormatInfo(dateRangeFormat = DateRangeFormat(Reuters))

      val today = Day.today()
      val sampleMonths = List(today.asMonthObject, today.addMonths(1).asMonthObject)

      val standardSampleText = sampleMonths.map(m => PeriodPivotFormatter.format(m, standardExtraInfo).text).mkString("(", ", ", " ...)")
      val shortSampleText = sampleMonths.map(m => PeriodPivotFormatter.format(m, shortExtraInfo).text).mkString("(", ", ", " ...)")
      val numericSampleText = sampleMonths.map(m => PeriodPivotFormatter.format(m, numericExtraInfo).text).mkString("(", ", ", " ...)")
      val reutersSampleText = sampleMonths.map(m => PeriodPivotFormatter.format(m, reutersExtraInfo).text).mkString("(", ", ", " ...)")

      val standardLabel = new Label(standardSampleText)
      val shortLabel = new Label(shortSampleText)
      val numericLabel = new Label(numericSampleText)
      val reutersLabel = new Label(reutersSampleText)

      def createButton(name:String) = new RadioButton(name) {reactions += {case ButtonClicked(_) => saveSettings()}}

      val standardButton = createButton("Standard")
      val shortButton = createButton("Short")
      val numericButton = createButton("Numeric")
      val reutersButton = createButton("Reuters")
      val group = new ScalaButtonGroup(standardButton, shortButton, numericButton, reutersButton)

      val buttonToType = Map[ScalaAbstractButton,DateRangeFormat](
        standardButton -> DateRangeFormat(Standard),
        shortButton -> DateRangeFormat(Short),
        numericButton -> DateRangeFormat(Numeric),
        reutersButton -> DateRangeFormat(Reuters))
      val typeToButton = buttonToType.map{_.swap}

      add(LabelWithSeparator("Month Format"), "spanx, growx, wrap")
      add(standardButton, "skip1")
      add(standardLabel, "wrap")
      add(shortButton, "skip 1")
      add(shortLabel, "wrap")
      add(numericButton, "skip 1")
      add(numericLabel, "wrap")
      add(reutersButton, "skip 1")
      add(reutersLabel)

      def dateRangeFormat = {
        buttonToType(group.selected.get)
      }
      def dateRangeFormat_=(drf:DateRangeFormat) {
        group.select(typeToButton(drf))
      }

      dateRangeFormat = currentSettings.dateRangeFormat
    }

    new MigPanel("insets 0") {
      add(decimalPlacesPanel, "gapright unrel, ay top")
      add(dateRangeFormatPanel, "ay top")
      reactions += {
        case UserSettingUpdated(ExtraFormattingInfo) => {
          val extraFormatInfo = context.getSetting(ExtraFormattingInfo)
          decimalPlacesPanel.decimalPlaces = extraFormatInfo.decimalPlaces
          dateRangeFormatPanel.dateRangeFormat = extraFormatInfo.dateRangeFormat
        }
      }
      listenTo(context.remotePublisher)
    } :: Nil



  }
}