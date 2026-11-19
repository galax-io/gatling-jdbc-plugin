package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorSlashClosingBraceBindingSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolator_slash_closing_brace_binding"

  test("FR-002 binds the parameter after an H2 slash comment with a standalone closing brace") {
    val sql  = "SELECT 1 // it's first\n, 2 // it's second }\n, {id} AS id"
    val rows = Await.result(client.executeSelect(sql, Seq("id" -> IntParam(7)))(identity), 10.seconds).get

    rows.head("ID") shouldBe 7
  }
}
