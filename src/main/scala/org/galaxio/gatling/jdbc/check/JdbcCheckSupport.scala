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

  val AllRecordPreparer: Preparer[AllRecordResult, AllRecordResult] = something => something.success

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

  val allRecordsCheck: JdbcAllRecordsCheck.type = JdbcAllRecordsCheck

}
