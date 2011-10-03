package starling.marketdata

trait ReferenceDataLookup {
  def areaFor(code: AreaCode): Area
  def areaFor(code: NeptuneCountryCode): Area
  def gradeFor(code: GradeCode): Grade
  def contractLocationFor(code: ContractualLocationCode): ContractualLocation
  def countryFor(code: NeptuneCountryCode): NeptuneCountry
  def incotermFor(code: IncotermCode): Incoterm
}

case class Incoterm(code: IncotermCode, name: String) {
  override def toString = name
}

object ReferenceDataLookup {
  object Null extends ReferenceDataLookup {
    def areaFor(code: AreaCode) = Area(code, unknownName)
    def areaFor(code: NeptuneCountryCode) =  areaFor(AreaCode(unknownName))
    def gradeFor(code: GradeCode) = Grade(code, unknownName)
    def contractLocationFor(code: ContractualLocationCode) = ContractualLocation(code, unknownName)
    def countryFor(code: NeptuneCountryCode) = NeptuneCountry(code, unknownName, areaFor(AreaCode(unknownName)))
    def incotermFor(code: IncotermCode) = Incoterm(code, unknownName)

    private val unknownName = "ReferenceDataLookup.Null"
  }
}