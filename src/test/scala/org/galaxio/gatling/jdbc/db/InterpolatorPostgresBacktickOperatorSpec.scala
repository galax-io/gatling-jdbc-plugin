package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.postgresql.core.Parser
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class InterpolatorPostgresBacktickOperatorSpec extends AnyFunSuite with Matchers {
  test("FR-002 binds the parameter after a PostgreSQL backtick operator and a string") {
    val sql    = "SELECT 1 <`> 'tag` {inside}'::text, {id}::int"
    val result = Interpolator.interpolate(sql)
    val parsed = Parser.parseJdbcSql(result.queryString, true, true, false, false, false).get(0)

    parsed.nativeSql shouldBe "SELECT 1 <`> 'tag` {inside}'::text,  $1::int"
    result.m shouldBe Map("id" -> List(1))
  }
}
