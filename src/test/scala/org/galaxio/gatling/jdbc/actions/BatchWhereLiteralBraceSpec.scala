package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions.{BatchActionBuilder, BatchUpdateValuesStepAction}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class BatchWhereLiteralBraceSpec extends AnyFunSuite with Matchers with JdbcActionSpecFixture {
  test("bind only the real WHERE parameter next to a brace literal") {
    val update = BatchUpdateValuesStepAction(_ => Success("items"), Seq("status" -> (_ => Success("new"))))
      .where("tags = '{x,y}' AND id = {id}", "id" -> (_ => Success(2)))

    val tc = buildRealTestContext("batch_where_literal_brace", 2, config)
    try {
      Await
        .result(
          tc.client.executeRaw("CREATE TABLE IF NOT EXISTS items (id INT, tags VARCHAR, status VARCHAR)")(identity),
          10.seconds,
        )
        .get
      Await.result(tc.client.executeRaw("DELETE FROM items")(identity), 10.seconds).get
      Await
        .result(
          tc.client
            .executeRaw("INSERT INTO items VALUES (1, '{x,y}', 'old'), (2, '{x,y}', 'old'), (3, '{z}', 'old')")(identity),
          10.seconds,
        )
        .get

      val capture = new CaptureAction()
      BatchActionBuilder(_ => Success("update tagged item"), Seq(update)).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      val rows    =
        Await.result(tc.client.executeSelect("SELECT status FROM items ORDER BY id", Seq.empty)(identity), 10.seconds).get
      rows.map(_("STATUS")) shouldBe List("old", "new", "old")
    } finally tc.close()
  }
}
