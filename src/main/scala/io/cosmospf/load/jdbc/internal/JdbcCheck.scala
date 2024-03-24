package io.cosmospf.load.jdbc.internal

import io.gatling.core.check._
import io.gatling.core.check.Check.Simple
import io.gatling.core.check.CheckBuilder.Final
import io.cosmospf.load.jdbc.JdbcCheck
import io.cosmospf.load.jdbc.check.JdbcCheckSupport
import io.gatling.core.Predef.find2Final
import io.gatling.core.check.CheckBuilder.Final._
import scala.jdk.CollectionConverters.CollectionHasAsScala

object JdbcCheck extends JdbcCheckSupport {

  def simpleJavaCheck(checkType: io.cosmospf.load.javaapi.check.simpleCheckType): Simple[AllRecordResult] = {
    checkType match {
      case io.cosmospf.load.javaapi.check.simpleCheckType.NonEmpty =>
        simpleCheck(x => x.nonEmpty).asInstanceOf[Simple[AllRecordResult]]
      case io.cosmospf.load.javaapi.check.simpleCheckType.Empty    =>
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
