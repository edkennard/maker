package starling.startserver.apps

import com.trafigura.services._
import com.trafigura.services.marketdata._
import starling.daterange.Day
import valuation.TitanMarketDataIdentifier
import starling.client.BouncyRMIServiceApi


object MarketDataClient {
  def main(args: Array[String]) = test(BouncyRMIServiceApi())

  def test(serviceApi: ServiceApi) = serviceApi.using { marketData: MarketDataServiceApi =>

    val day = Day(2011, 6, 24)
    val date = day.toLocalDate
    val observationDay = TitanSerializableDate(date)
    val titanSnapshotIdentifier: TitanSnapshotIdentifier = TitanSnapshotIdentifier("4439")
    val marketDataID = TitanMarketDataIdentifier(titanSnapshotIdentifier, day)

    ///////////////////
    // Spot FX Examples
    ///////////////////

    { // Latest SpotFX
      val latestSnapshot: TitanSnapshotIdentifier = marketData.latestSnapshotID().getOrElse(throw new Exception("No snapshots"))
      val latestSpotFXRates: List[SpotFXRate] = marketData.getSpotFXRates(marketDataID)

      println("latest getSpotFXRates:...")
      latestSpotFXRates.foreach(println)
    }

    { // SpotFX for a given snapshot
      val spotFXRates: List[SpotFXRate] = marketData.getSpotFXRates(marketDataID)

      println("getSpotFXRates:...")
      spotFXRates.foreach(println)
    }

    { // Specific SpotFX
      val currencies = TitanSerializableCurrency.titanCurrencies.map(TitanSerializableCurrency(_))

      for (from <- currencies; to <- currencies) {
        val spotFXRate: SpotFXRate = marketData.getSpotFXRate(marketDataID, from, to)

        println("getSpotFXRate(%s, %s, %s) = %s".format(marketDataID, from, to, spotFXRate))
      }
    }

    ///////////////////////////////////
    // Reference Interest Rate Examples
    ///////////////////////////////////

    {
      val interestRates: List[ReferenceInterestRate] = marketData.getReferenceInterestRates(marketDataID)

      println("getReferenceInterestRates:...")
      interestRates.foreach(println)
    }

    {
      val interestRate: ReferenceInterestRate = marketData.getReferenceInterestRate(
        marketDataID, ReferenceRateSource("LIBOR"), Maturity.get("1M"), TitanSerializableCurrency("GBP"))

      println("getReferenceInterestRate: " + interestRate)
    }

    {
      val domesticRates: List[ReferenceInterestRate] = {
        val from = Day(2011, 7, 1)
        val to = Day(2011, 7, 6)

        marketData.getReferenceInterestRates(titanSnapshotIdentifier, from, to, ReferenceRateSource("Domestic Rate"),
          Maturity.get("ON"), TitanSerializableCurrency("RMB"))
      }

      domesticRates.foreach(println)
    }
  }
}