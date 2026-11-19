package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorSlashCommentBindingSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolation_slash_comment_binding"

  test("bind the parameter on the line after an H2 slash comment") {
    val sql  = "SELECT 1 AS n // it's a comment\n, {id} AS id"
    val rows = Await.result(client.executeSelect(sql, Seq("id" -> IntParam(7)))(identity), 10.seconds).get

    rows shouldBe List(Map("N" -> 1, "ID" -> 7))
  }
}
