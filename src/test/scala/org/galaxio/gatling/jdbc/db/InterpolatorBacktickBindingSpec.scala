package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InterpolatorBacktickBindingSpec extends AnyFunSuite with Matchers with H2ClientSpecFixture {
  override protected val dbName = "interpolation_backtick_binding"

  test("bind insert values after a backtick identifier containing an apostrophe") {
    val table = "`customer's orders`"
    SqlIdentifier.isValid(table) shouldBe true
    Await.result(client.executeRaw(s"CREATE TABLE IF NOT EXISTS $table (id INT)")(identity), 10.seconds).get

    val inserted = Await
      .result(
        client.executeUpdate(s"INSERT INTO $table (id) VALUES({id})", Seq("id" -> IntParam(7)))(identity),
        10.seconds,
      )
      .get
    inserted shouldBe 1
  }
}
