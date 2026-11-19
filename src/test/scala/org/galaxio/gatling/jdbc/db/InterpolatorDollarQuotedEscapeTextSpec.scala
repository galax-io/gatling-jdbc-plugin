package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorDollarQuotedEscapeTextSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolator_dollar_quoted_escape_text"

  test("FR-002 binds the outer parameter when an H2 dollar string contains escape-string syntax") {
    val sql  = """SELECT $$ E'one\' '{inside}' $$ AS message, {id} AS id"""
    val rows = Await.result(client.executeSelect(sql, Seq("id" -> IntParam(7)))(identity), 10.seconds).get

    rows shouldBe List(Map("MESSAGE" -> """ E'one\' '{inside}' """, "ID" -> 7))
  }
}
