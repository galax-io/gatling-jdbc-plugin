package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorSpacedArrayBindingSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolator_spaced_array_binding"

  test("FR-002 binds the named parameter inside a spaced H2 ARRAY constructor") {
    val sql  = "SELECT (ARRAY ['a]b', CAST({id} AS VARCHAR)])[2] AS bound_value"
    val rows = Await.result(client.executeSelect(sql, Seq("id" -> IntParam(7)))(identity), 10.seconds).get

    rows shouldBe List(Map("BOUND_VALUE" -> "7"))
  }
}
