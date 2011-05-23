package starling.pivot.controller

import starling.pivot.model.AxisCell
import starling.pivot.TableCell
import starling.quantity.UOM

case class PivotGrid(
        rowData:Array[Array[AxisCell]],
        colData:Array[Array[AxisCell]],
        mainData:Array[Array[TableCell]],
        colUOMS:Array[UOM] = Array()) {

  lazy val hasData = mainData.exists(_.exists(_.text != ""))

  def combinedData: Array[Array[Object]] = {
    val rowWidth = rowData.headOption.map(_.size).getOrElse(0)
    val emptyRow = Array.fill(rowWidth)("")

    val columns: Array[Array[Object]] = colData.map(col => emptyRow ++ col.map(_.asInstanceOf[Object]))

    val rows = rowData.zip(mainData).map { case (rowData, mainData) =>
      rowData.map(_.asInstanceOf[Object]) ++ mainData.map(_.asInstanceOf[Object])
    }

    columns ++ rows
  }
}