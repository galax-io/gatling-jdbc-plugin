package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorDollarQuoteBindingSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolation_dollar_quote_binding"

  test("bind the parameter after a dollar-quoted literal containing an apostrophe") {
    val sql  = "SELECT $$it's fine$$ AS message, {id} AS id"
    val rows = Await.result(client.executeSelect(sql, Seq("id" -> IntParam(7)))(identity), 10.seconds).get

    rows shouldBe List(Map("MESSAGE" -> "it's fine", "ID" -> 7))
  }
}
