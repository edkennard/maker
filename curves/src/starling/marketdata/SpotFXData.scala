package starling.marketdata

import starling.quantity.{UOMSymbol, Quantity, UOM}
import starling.pivot._


object SpotFXDataType extends MarketDataType {
  type dataType = SpotFXData
  type keyType = SpotFXDataKey
  val keys = UOM.currencies.filterNot(_ == UOM.USD).map(s=>SpotFXDataKey(s))
  val currencyField = FieldDetails("Currency")
  val rateField = new PivotQuantityFieldDetails("Rate")
  def createKey(row: Row) = SpotFXDataKey(UOM.parseCurrency(row.string(currencyField)))
  def createValue(rows: List[Row]) = SpotFXData(Row.singleRow(rows, "spot fx rate").pivotQuantity(rateField).quantityValue.get)

  val initialPivotState = PivotFieldsState(
    dataFields=List(rateField.field),
    rowFields=List(currencyField.field)
  )
  def marketDataKeyFields = Set(currencyField.field)
  def keyFields = Set(currencyField.field)
  def valueFields = List(rateField.field)
  val fields = List(currencyField, rateField)

  def rows(key: SpotFXDataKey, data: SpotFXData, referenceDataLookup: ReferenceDataLookup) = List(Row(
    currencyField.field → key.ccy.toString,
    rateField.field → PivotQuantity(data.rate)
  ))
}

/**
 * Against USD. So ccy/USD
 */
case class SpotFXDataKey(ccy: UOM) extends MarketDataKey {
  type marketDataType = SpotFXData
  type marketDataDBType = SpotFXData
  def dataType = SpotFXDataType
  def subTypeKey = ccy.toString
  def fieldValues(referenceDataLookup: ReferenceDataLookup) = Row(SpotFXDataType.currencyField.field → ccy.toString)
}

//For Example 0.0125 USD / JPY
case class SpotFXData(rate: Quantity) extends MarketData {
  override def size = 1
}

