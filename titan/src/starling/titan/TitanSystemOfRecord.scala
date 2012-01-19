package starling.titan

import starling.systemofrecord.SystemOfRecord
import starling.daterange.Day
import starling.pivot.Field
import starling.instrument.{Trade, TradeAttributes}
import java.lang.UnsupportedOperationException
import starling.instrument.ErrorInstrument
import com.trafigura.edm.logistics.inventory._
import starling.utils.Log
import com.trafigura.edm.trademgmt.trades.{PhysicalTrade => EDMPhysicalTrade}
import starling.instrument.physical._
import starling.quantity.{Quantity, Percentage}
import starling.titan.EDMConversions._
import runtime.ScalaRunTime



class TitanSystemOfRecord(manager : TitanTradeStoreManager)
  extends SystemOfRecord with Log {

   def allTrades : Seq[Trade] = {
    manager.reimportAllTrades
    val allTrades = manager.allStarlingTrades
    val duplicates = allTrades.groupBy(_.tradeID).filter(kv => kv._2.size > 1)
    assert(duplicates.isEmpty, "duplicates found: \n" + duplicates.mkString("\n"))
    allTrades.toList
  }

  protected def readers = throw new UnsupportedOperationException()
}

case class TitanTradeAttributes(
  quotaID : String,
  quotaQuantity : Quantity,
  titanTradeID : String,
  groupCompany : String,
  comment : String,
  submitted : Day,
  shape : String,
  contractFinalised : String,
  tolerancePlus : Percentage,
  toleranceMinus : Percentage,
  eventIDs : List[String]) extends TradeAttributes {

  if (quotaID == null) {
    println("quota was null")
  }
  require(quotaID != null, "quotaID cannot be null")
  require(titanTradeID != null, "titanTradeID cannot be null")

  import TitanTradeStore._

  def persistedDetails = Map(
    quotaID_str -> quotaID,
    quotaQuantity_str -> quotaQuantity,
    titanTradeID_str -> titanTradeID,
    groupCompany_str -> groupCompany,
    comment_str -> comment,
    submitted_str -> submitted,
    shape_str -> shape,
    contractFinalised_str -> contractFinalised,
    tolerancePlus_str -> tolerancePlus,
    toleranceMinus_str -> toleranceMinus,
    eventIDs_str -> eventIDs.mkString(",")
  )

  override def createFieldValues = persistedDetails.map{
    case (k, v) => Field(k) -> v
  }

  override def hashCode() = ScalaRunTime._hashCode(copy(eventIDs = Nil, comment = ""))

  // We don't want to use eventID in hashCode and equals as it changes every time a trade
  // is published on the bus, even if the trade has not changed. If we include it in hashCode
  // and equals it looks like the trade has changed and we store a new revision.
  override def equals(obj:Any) = obj match {
    case other:TitanTradeAttributes => {
      ScalaRunTime._equals(this, other.copy(eventIDs = this.eventIDs, comment = this.comment))
    }
    case _ => false
  }
}

object TitanTradeAttributes{
  val dummyDate = Day(1980, 1, 1)
  def errorAttributes(edmTradeId : String, evntIDs : List[String]) = {
    TitanTradeAttributes(
      quotaID = "",
      quotaQuantity = Quantity.NULL,
      titanTradeID = edmTradeId,
      groupCompany = "",
      comment = "",
      submitted = dummyDate,
      shape = "",
      contractFinalised = "",
      tolerancePlus = Percentage(0),
      toleranceMinus = Percentage(0),
      eventIDs = evntIDs
    )
  }
}

