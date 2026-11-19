package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import org.galaxio.gatling.jdbc.db.testsupport.{H2, H2ClientSpecFixture}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorBracketIdentifierBindingSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolation_bracket_identifier_binding"

  override protected def buildDataSource(): HikariDataSource = {
    val cfg = H2.config(dbName, poolSize)
    cfg.setJdbcUrl(H2.jdbcUrl(dbName) + ";MODE=MSSQLServer")
    new HikariDataSource(cfg)
  }

  test("bind the parameter after a bracketed identifier containing an apostrophe") {
    val sql  = "SELECT 1 AS [customer's orders], {id} AS id"
    val rows = Await.result(client.executeSelect(sql, Seq("id" -> IntParam(7)))(identity), 10.seconds).get

    rows.head("ID") shouldBe 7
  }
}
