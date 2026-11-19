package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorIndependentDialectBindingSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolator_independent_dialect_binding"

  test("FR-002 binds the parameter after a slash comment following a nested H2 array") {
    val sql  = "SELECT 1 AS n // it's first\n" +
      ", CARDINALITY(ARRAY[ARRAY [1]]) AS array_size, 2 AS m // it's second\n" +
      ", {id} AS id"
    val rows = Await.result(client.executeSelect(sql, Seq("id" -> IntParam(7)))(identity), 10.seconds).get

    rows.head("ID") shouldBe 7
  }
}
