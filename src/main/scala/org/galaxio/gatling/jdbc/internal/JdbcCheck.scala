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

  private def toScalaCheck(javaCheck: Object): JdbcCheck = {
    javaCheck match {
      case simpleCheck: Simple[_]                            => simpleCheck.asInstanceOf[Simple[AllRecordResult]]
      case defaultCheck: CheckBuilder.Final.Default[_, _, _] =>
        checkBuilder2JdbcCheck(
          defaultCheck.asInstanceOf[Default[JdbcAllRecordCheckType, AllRecordResult, AllRecordResult]],
        )
      case unknown                                           => throw new IllegalArgumentException(s"JDBC DSL doesn't support $unknown")
    }
  }

  def toScalaChecks(javaChecks: java.util.List[Object]): Seq[JdbcCheck] =
    javaChecks.asScala.map(x => toScalaCheck(x)).toSeq
}
