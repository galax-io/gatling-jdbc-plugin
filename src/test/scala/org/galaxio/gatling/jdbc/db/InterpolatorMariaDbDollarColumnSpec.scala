package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class InterpolatorMariaDbDollarColumnSpec extends AnyFunSuite with Matchers {
  test("FR-002 binds the parameter after an ordinary MariaDB dollar column and string") {
    val result = Interpolator.interpolate("SELECT $col$, 'total $col$', {id} FROM t")

    result.queryString shouldBe "SELECT $col$, 'total $col$',  ? FROM t"
    result.m shouldBe Map("id" -> List(1))
  }
}
