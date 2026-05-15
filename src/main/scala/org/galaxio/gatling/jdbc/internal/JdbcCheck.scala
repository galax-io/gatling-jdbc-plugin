package org.galaxio.gatling.jdbc.internal

import io.gatling.core.Predef.find2Final
import io.gatling.core.check.Check.Simple
import io.gatling.core.check.CheckBuilder.Final
import io.gatling.core.check.CheckBuilder.Final._
import io.gatling.core.check._
import org.galaxio.gatling.javaapi.check
import org.galaxio.gatling.javaapi.check._
import org.galaxio.gatling.jdbc.JdbcCheck
import org.galaxio.gatling.jdbc.check.JdbcCheckSupport

import scala.jdk.CollectionConverters.CollectionHasAsScala

object JdbcCheck extends JdbcCheckSupport {

  def simpleJavaCheck(checkType: simpleCheckType): Simple[AllRecordResult] = {
    checkType match {
      case simpleCheckType.NonEmpty    =>
        simpleCheck(x => x.nonEmpty).asInstanceOf[Simple[AllRecordResult]]
      case check.simpleCheckType.Empty =>
        simpleCheck(x => x.isEmpty).asInstanceOf[Simple[AllRecordResult]]
    }
  }

  def results(): Final[JdbcAllRecordCheckType, AllRecordResult] =
    find2Final(allResults)

  def javaRow(rowIndex: Int): Final[JdbcAllRecordCheckType, Map[String, Any]] =
    find2Final(row(rowIndex).asInstanceOf[CheckBuilder.Find[JdbcAllRecordCheckType, Map[String, Any], AllRecordResult]])

  def javaColumn(columnName: String): Final[JdbcAllRecordCheckType, List[Any]] =
    find2Final(column(columnName).asInstanceOf[CheckBuilder.Find[JdbcAllRecordCheckType, List[Any], AllRecordResult]])

  def javaCell(columnName: String, rowIndex: Int): Final[JdbcAllRecordCheckType, Any] =
    find2Final(cell(columnName, rowIndex).asInstanceOf[CheckBuilder.Find[JdbcAllRecordCheckType, Any, AllRecordResult]])

  private def toScalaCheck(javaCheck: Object): JdbcCheck = {
    javaCheck match {
      case simpleCheck: Simple[_]                            => simpleCheck.asInstanceOf[Simple[AllRecordResult]]
      case defaultCheck: CheckBuilder.Final.Default[_, _, _] =>
        checkBuilder2JdbcCheck(
          defaultCheck.asInstanceOf[Default[JdbcAllRecordCheckType, AllRecordResult, Any]],
        )
      case findCheck: CheckBuilder.Find.Default[_, _, _]     =>
        findCheckBuilder2JdbcCheck(
          findCheck.asInstanceOf[CheckBuilder.Find.Default[JdbcAllRecordCheckType, AllRecordResult, Any]],
        )
      case unknown                                           => throw new IllegalArgumentException(s"JDBC DSL doesn't support $unknown")
    }
  }

  def toScalaChecks(javaChecks: java.util.List[Object]): Seq[JdbcCheck] =
    javaChecks.asScala.map(x => toScalaCheck(x)).toSeq
}
