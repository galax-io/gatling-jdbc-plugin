package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class InterpolatorOracleQuoteWithBracesSpec extends AnyFunSuite with Matchers {
  test("FR-002 binds the parameter after an Oracle q literal containing an apostrophe and braces") {
    val result = Interpolator.interpolate("SELECT q'[it's {a}]', {id} FROM DUAL")

    result.queryString shouldBe "SELECT q'[it's {a}]',  ? FROM DUAL"
    result.m shouldBe Map("id" -> List(1))
  }
}
