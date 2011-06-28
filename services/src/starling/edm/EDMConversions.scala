package starling.edm

import starling.quantity.UOMSymbol._
import starling.daterange.{Tenor, SimpleDateRange, Day}

import starling.utils.ImplicitConversions._
import starling.quantity.{UOMSymbol, Percentage, UOM, Quantity}
import com.trafigura.edm.shared.types.{Currency => TitanCurrency, Date => TitanDate, DateRange => TitanDateRange,
                                       Percentage => TitanPercentage, Quantity => TitanQuantity,
                                       CompoundUOM, UnitComponent, FundamentalUOM}

import com.trafigura.services._
import com.trafigura.services.marketdata.Maturity

case class InvalidUomException(msg : String) extends Exception(msg)

object EDMConversions {
  // Starling implicits
  implicit def enrichQuantity(q: Quantity) = new {
    def toTitan = toTitanQuantity(q)
    def toSerializable = toTitanSerializableQuantity(q)
  }

  implicit def enrichTenor(tenor: Tenor) = new {
    def toTitan: Maturity = Maturity.get(tenor.toString)
  }

  implicit def enrichUOM(uom: UOM) = new {
    def titanCurrency: Option[TitanCurrency] = toTitan.map(edm => TitanCurrency().update(_.name = edm.name))
    def serializableCurrency: Option[TitanSerializableCurrency] =
      starlingUomToEdmUomName.get(uom).map(fuom => TitanSerializableCurrency(fuom))
    def toTitan: Option[FundamentalUOM] = starlingUomToEdmUomName.get(uom).map(FundamentalUOM(_))
  }

  implicit def enrichPercentage(percentage: Percentage) = new {
    def toTitan = TitanPercentage(Some(percentage.value))
    def toSerializable = TitanSerializablePercentage(percentage.value)
  }

  implicit def enrichDay(day: Day) = new {
    def toTitan = TitanDate(day.toLocalDate)
    def toSerializable = TitanSerializableDate(day.toLocalDate)
  }

  // 'Serializable' implicits
  implicit def enrichSerializableDate(date: TitanSerializableDate) = new {
    def fromSerializable = Day.fromLocalDate(date.value)
  }

  implicit def enrichSerializableCurrency(currency: TitanSerializableCurrency) = new {
    def fromSerializable = edmToStarlingUomSymbol(currency.name).asUOM
  }

  // Titan implicits
  implicit def enrichTitanQuantity(q: TitanQuantity) = new {
    def fromTitan = fromTitanQuantity(q)
  }

  implicit def enrichTitanDate(date: TitanDate) = new {
    def fromTitan = Day.fromLocalDate(date.datex)
  }

  implicit def enrichTitanDateRange(dateRange: TitanDateRange) = new {
    def fromTitan = new SimpleDateRange(startDay, endDay)
    def contains(date: TitanDate) = fromTitan.contains(date.fromTitan)
    def startDay = Day.fromLocalDate(dateRange.startDate)
    def endDay = Day.fromLocalDate(dateRange.endDate)
  }

  implicit def enrichFundamentalUOM(uom: FundamentalUOM) = new {
    def fromTitan = edmToStarlingUomSymbol(uom.name).asUOM
    def titanCurrency: TitanCurrency = TitanCurrency().update(_.name = uom.name)
  }

  implicit def fromTitanQuantity(q : TitanQuantity) : Quantity = {
    val amount = q.amount match {
      case Some(amt) => amt
      case None => throw new Exception("Invalid quantity - no amount")
    }  // No idea why this is optional in EDM
    val uom = UOM.fromSymbolMap(q.uom.components.map {
      case uc => {
        edmToStarlingUomSymbol.get(uc.fundamental.name) match {
          case Some(uomSymbol) => uomSymbol -> uc.exponent
          case None => throw new InvalidUomException(uc.fundamental.name)
        }
      }
    }.toMap)

    Quantity(amount, uom)
  }

  implicit def toTitanQuantity(q : Quantity) : TitanQuantity = {
    val symbolPowers = q.uom.asSymbolMap()

    // create edm UOMs, EDM symbol list is GBP, USD, JPY, RMB, MTS, LBS

    val unitComponents = symbolPowers.map{
      case (starlingUOMSymbol, power) => UnitComponent(
        oid = 0,
        exponent = power,
        fundamental = FundamentalUOM(starlingUomSymbolToEdmUom.getOrElse(starlingUOMSymbol, starlingUOMSymbol.toString))
       )
    }.toList

    TitanQuantity(Some(q.value), CompoundUOM(unitComponents))
  }

  implicit def toTitanSerializableQuantity(q : Quantity) : TitanSerializableQuantity = {
    val symbolPowers = q.uom.asSymbolMap()

    val uomMap = symbolPowers.map{
      case (starlingUOMSymbol, power) => (
        starlingUomSymbolToEdmUom.getOrElse(starlingUOMSymbol, starlingUOMSymbol.toString),
        power
      )
    }.toMap
    TitanSerializableQuantity(q.value, uomMap)
  }
  
  val starlingUomSymbolToEdmUom = Map(
    aed -> "AED",
    gbp -> "GBP",
    eur -> "EUR",
    zar -> "ZAR",
    usd -> "USD",
    jpy -> "JPY",
    cny -> "RMB",
    TONNE_SYMBOL -> "MTS",
    POUND_SYMBOL -> "LBS"

//    LegacyCurrency.Aed -> "AED",
//    LegacyCurrency.Ecb -> "ECB",
//    LegacyCurrency.Eur -> "EUR",
//    LegacyCurrency.Fx1 -> "FX1",
//    LegacyCurrency.Gbp -> "GBP",
//    LegacyCurrency.Itl -> "ITL",
//    LegacyCurrency.Jpy -> "JPY",
//    LegacyCurrency.Rmb -> "RMB",
//    LegacyCurrency.Usd -> "USD",
//    LegacyCurrency.Zar -> "ZAR"
  )

  val starlingUomToEdmUomName: Map[UOM, String] = starlingUomSymbolToEdmUom.mapKeys(_.asUOM)
  val edmToStarlingUomSymbol: Map[String, UOMSymbol] = starlingUomSymbolToEdmUom.map(_.swap) + ("ECB" -> eur)
}
