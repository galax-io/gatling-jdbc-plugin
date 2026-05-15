package org.galaxio.gatling.jdbc.check

import io.gatling.commons.stats.OK
import io.gatling.commons.validation.Failure
import io.gatling.core.Predef._
import io.gatling.core.check.Check
import io.gatling.core.session.Session
import org.galaxio.gatling.jdbc.JdbcCheck
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{HashMap => JHashMap}

class JdbcCheckSupportSpec extends AnyFlatSpec with Matchers with JdbcCheckSupport {

  private def session(): Session =
    new Session("jdbc-check-support-spec", 1L, Map.empty, OK, Nil, Session.NothingOnExit, null)

  private def runChecks(response: AllRecordResult, checks: JdbcCheck*): (Session, Option[Failure]) = {
    val (updatedSession, error) = Check.check(response, session(), checks.toList, new JHashMap[Any, Any]())
    (updatedSession.asInstanceOf[Session], error)
  }

  private val rows = List(
    Map("ID" -> 1, "NAME" -> "Alice"),
    Map("ID" -> 2, "NAME" -> "Bob"),
  )

  "row" should "save a row by index" in {
    val (updatedSession, error) = runChecks(rows, row(0).saveAs("firstRow"))

    error shouldBe None
    updatedSession.attributes("firstRow") shouldBe Map("ID" -> 1, "NAME" -> "Alice")
  }

  "column" should "save all values for the requested column" in {
    val (updatedSession, error) = runChecks(rows, column("NAME").saveAs("names"))

    error shouldBe None
    updatedSession.attributes("names") shouldBe List("Alice", "Bob")
  }

  "cell" should "support value assertions" in {
    val (_, error) = runChecks(rows, cell("NAME", 1).is("Bob"))

    error shouldBe None
  }

  it should "fail with a useful message when the column is missing" in {
    val (_, error) = runChecks(rows, cell("AGE", 0).exists)

    error match {
      case Some(Failure(message)) =>
        message should include("Column 'AGE' was not found")
        message should include("ID")
        message should include("NAME")
      case other                  => fail(s"Expected a failed validation, got $other")
    }
  }

  "row" should "fail with a useful message when the index is out of bounds" in {
    val (_, error) = runChecks(rows, row(3).exists)

    error match {
      case Some(Failure(message)) =>
        message should include("Row index 3 is out of bounds")
        message should include("2 row(s)")
      case other                  => fail(s"Expected a failed validation, got $other")
    }
  }
}
