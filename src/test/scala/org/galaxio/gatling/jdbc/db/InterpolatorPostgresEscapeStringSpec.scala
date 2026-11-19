package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.postgresql.core.Parser
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class InterpolatorPostgresEscapeStringSpec extends AnyFunSuite with Matchers {
  test("bind the parameter after a PostgreSQL escape string") {
    val sql          = """SELECT E'it\'s fine' AS message, {id}::int AS id"""
    val interpolated = Interpolator.interpolate(sql)
    val parsed       = Parser.parseJdbcSql(interpolated.queryString, true, true, false, false, false).get(0)

    parsed.bindPositions.length shouldBe 1
    parsed.nativeSql shouldBe """SELECT E'it\'s fine' AS message,  $1::int AS id"""
    interpolated.m shouldBe Map("id" -> List(1))
  }
}
