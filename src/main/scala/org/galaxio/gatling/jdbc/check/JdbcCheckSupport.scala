package org.galaxio.gatling.jdbc.check

import io.gatling.commons.validation._
import io.gatling.core.check.Check.PreparedCache
import io.gatling.core.check._
import io.gatling.core.session._
import org.galaxio.gatling.jdbc.JdbcCheck

import scala.annotation.implicitNotFound

trait JdbcCheckSupport {
  trait JdbcAllRecordCheckType

  type AllRecordResult = List[Map[String, Any]]
  type JdbcRow         = Map[String, Any]
  type JdbcColumn      = List[Any]

  val AllRecordPreparer: Preparer[AllRecordResult, AllRecordResult] = something => something.success

  private def availableColumns(response: AllRecordResult): String =
    response.headOption
      .map(_.keys.toList.sorted.mkString(", "))
      .getOrElse("<none>")

  private def extractRow(response: AllRecordResult, rowIndex: Int): Validation[JdbcRow] =
    response.lift(rowIndex) match {
      case Some(row) => row.success
      case None      => s"Row index $rowIndex is out of bounds for ${response.size} row(s)".failure
    }

  private def extractColumnValue(row: JdbcRow, columnName: String, response: AllRecordResult): Validation[Any] =
    row.get(columnName) match {
      case Some(value) => value.success
      case None        =>
        s"Column '$columnName' was not found. Available columns: ${availableColumns(response)}".failure
    }

  private def extractColumn(response: AllRecordResult, columnName: String): Validation[JdbcColumn] =
    response.foldLeft(List.empty[Any].success) { (acc, row) =>
      for {
        values <- acc
        value  <- extractColumnValue(row, columnName, response)
      } yield values :+ value
    }

  def simpleCheck(f: AllRecordResult => Boolean): JdbcCheck =
    Check.Simple(
      (response: AllRecordResult, _: Session, _: PreparedCache) =>
        if (f(response)) {
          CheckResult.NoopCheckResultSuccess
        } else {
          "Jdbc check failed".failure
        },
      None,
    )

  @implicitNotFound("Could not find a CheckMaterializer. This check might not be valid for JDBC.")
  implicit def checkBuilder2JdbcCheck[T, P, X](checkBuilder: CheckBuilder[T, P])(implicit
      materializer: CheckMaterializer[T, JdbcCheck, AllRecordResult, P],
  ): JdbcCheck =
    checkBuilder.build(materializer)

  @implicitNotFound("Could not find a CheckMaterializer. This check might not be valid for JDBC.")
  implicit def findCheckBuilder2JdbcCheck[T, P, X](find: CheckBuilder.Find[T, P, X])(implicit
      CheckMaterializer: CheckMaterializer[T, JdbcCheck, AllRecordResult, P],
  ): JdbcCheck =
    find.find.exists

  implicit val AllRecordCheckMaterializer
      : CheckMaterializer[JdbcAllRecordCheckType, JdbcCheck, AllRecordResult, AllRecordResult] =
    new CheckMaterializer[JdbcAllRecordCheckType, JdbcCheck, AllRecordResult, AllRecordResult](identity) {
      override protected def preparer: Preparer[AllRecordResult, AllRecordResult] = AllRecordPreparer
    }

  val AllRecordExtractor: Expression[Extractor[AllRecordResult, AllRecordResult]] =
    new Extractor[AllRecordResult, AllRecordResult] {
      override def name: String = "allRecords"

      override def apply(prepared: AllRecordResult): Validation[Option[AllRecordResult]] = Some(prepared).success

      override def arity: String = "find"
    }.expressionSuccess

  val AllRecordResults = new CheckBuilder.Find.Default[JdbcAllRecordCheckType, AllRecordResult, AllRecordResult](
    AllRecordExtractor,
    displayActualValue = true,
  )

  val allResults: CheckBuilder.Find.Default[JdbcAllRecordCheckType, AllRecordResult, AllRecordResult] = AllRecordResults

  def row(rowIndex: Int): CheckBuilder.Find.Default[JdbcAllRecordCheckType, AllRecordResult, JdbcRow] =
    new CheckBuilder.Find.Default(
      new Extractor[AllRecordResult, JdbcRow] {
        override def name: String = s"row($rowIndex)"

        override def apply(prepared: AllRecordResult): Validation[Option[JdbcRow]] =
          extractRow(prepared, rowIndex).map(Some(_))

        override def arity: String = "find"
      }.expressionSuccess,
      displayActualValue = true,
    )

  def column(columnName: String): CheckBuilder.Find.Default[JdbcAllRecordCheckType, AllRecordResult, JdbcColumn] =
    new CheckBuilder.Find.Default[JdbcAllRecordCheckType, AllRecordResult, JdbcColumn](
      new Extractor[AllRecordResult, JdbcColumn] {
        override def name: String = s"column($columnName)"

        override def apply(prepared: AllRecordResult): Validation[Option[JdbcColumn]] =
          extractColumn(prepared, columnName).map(Some(_))

        override def arity: String = "find"
      }.expressionSuccess,
      displayActualValue = true,
    )

  def cell(columnName: String, rowIndex: Int): CheckBuilder.Find.Default[JdbcAllRecordCheckType, AllRecordResult, Any] =
    new CheckBuilder.Find.Default(
      new Extractor[AllRecordResult, Any] {
        override def name: String = s"cell($columnName, $rowIndex)"

        override def apply(prepared: AllRecordResult): Validation[Option[Any]] =
          for {
            row   <- extractRow(prepared, rowIndex)
            value <- extractColumnValue(row, columnName, prepared)
          } yield Some(value)

        override def arity: String = "find"
      }.expressionSuccess,
      displayActualValue = true,
    )

  val allRecordsCheck: JdbcAllRecordsCheck.type = JdbcAllRecordsCheck

}
