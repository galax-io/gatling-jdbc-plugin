package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.KO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Map

/** Action-level regression test for issue #27: session.markAsFailed must be called on JDBC errors.
  *
  * Wires a real DBRawQueryAction and DBInsertAction against an H2 in-memory DB with deliberately bad SQL, captures the Session
  * forwarded to executeNext via a stub Action, and asserts session.isFailed == true. Removing `.markAsFailed` from the error
  * path in any JDBC action causes this test to fail.
  */
class ActionSessionFailureSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  // ─── test context builder ────────────────────────────────────────────────────

  private def buildTestContext(statsEngine: io.gatling.core.stats.StatsEngine): TestContext =
    buildRealTestContext("action_failure_test", 2, config, statsEngine)

  // ─── tests ───────────────────────────────────────────────────────────────────

  "DBRawQueryAction" should "forward a failed session to next when SQL is invalid" in {
    val stats   = new RecordingStatsEngine
    val tc      = buildTestContext(stats)
    val capture = new CaptureAction()

    try {
      val session = freshSession()

      val action = DBRawQueryAction(
        requestName = _ => io.gatling.commons.validation.Success("bad-sql-request"),
        query = _ => io.gatling.commons.validation.Success("THIS IS NOT VALID SQL AT ALL"),
        ctx = tc.ctx,
        next = capture,
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe true
      capture.invocationCount shouldBe 1
      stats.responses.filter(_.status == KO) should have size 1
    } finally {
      tc.close()
    }
  }

  "DBInsertAction" should "forward a failed session to next when the target table does not exist" in {
    val stats   = new RecordingStatsEngine
    val tc      = buildTestContext(stats)
    val capture = new CaptureAction()

    try {
      val session = freshSession(userId = 2L, attributes = Map("col" -> "value"))

      val action = DBInsertAction(
        requestName = _ => io.gatling.commons.validation.Success("insert-request"),
        tableName = _ => io.gatling.commons.validation.Success("ghost_table_that_does_not_exist"),
        columns = Seq("col"),
        next = capture,
        ctx = tc.ctx,
        sessionValues = Seq("col" -> (_ => io.gatling.commons.validation.Success("v": Any))),
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe true
      capture.invocationCount shouldBe 1
      stats.responses.filter(_.status == KO) should have size 1
    } finally {
      tc.close()
    }
  }
}
