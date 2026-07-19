package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.KO
import io.gatling.commons.validation.Success
import io.gatling.core.action.Action
import io.gatling.core.session.el._
import org.galaxio.gatling.jdbc.actions.actions.{BatchInsertAction, Columns}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Map

/** Regression tests for issue #77: an unresolved requestName EL expression must produce exactly one KO (logged under the
  * action's stable Gatling name), a failed session, and exactly one `next` invocation — never a hung virtual user.
  *
  * Before the fix, `ActionBase.crashOnFailure` re-resolved the failing expression and silently no-opped, so `next` was never
  * called: each missing-attribute test below fails by `awaitCapture` timeout on the unfixed code.
  */
class RequestNameCrashSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  /** Runs `mkAction` against H2 with a recording stats engine and asserts the one-KO/one-next contract. */
  private def assertCrashContract(dbName: String)(mkAction: (TestContext, CaptureAction) => Action): Unit = {
    val stats   = new RecordingStatsEngine
    val tc      = buildRealTestContext(dbName, 2, config, stats)
    val capture = new CaptureAction()

    try {
      val action = mkAction(tc, capture)
      action ! freshSession()

      withClue("next must be invoked despite the unresolved request name: ") {
        capture.awaitCapture() shouldBe true
      }
      capture.invocationCount shouldBe 1
      capture.capturedSession.isFailed shouldBe true

      val koResponses = stats.responses.filter(_.status == KO)
      withClue(s"exactly one KO stats entry expected, got: ${stats.responses}: ") {
        koResponses should have size 1
      }
      withClue("KO must be logged under the action's stable fallback name: ") {
        koResponses.head.requestName shouldBe action.name
      }
      stats.crashes should have size 1
      stats.crashes.head.requestName shouldBe action.name
    } finally {
      tc.close()
    }
  }

  private val unresolvedName = "#{missing}".el[String]

  // ─── one test per action type ────────────────────────────────────────────────

  "DBQueryAction" should "emit one KO and call next once when requestName EL cannot be resolved" in {
    assertCrashContract("crash_query") { (tc, capture) =>
      DBQueryAction(unresolvedName, _ => Success("SELECT 1"), Seq.empty, Seq.empty, capture, tc.ctx)
    }
  }

  "DBRawQueryAction" should "emit one KO and call next once when requestName EL cannot be resolved" in {
    assertCrashContract("crash_raw") { (tc, capture) =>
      DBRawQueryAction(unresolvedName, _ => Success("SELECT 1"), tc.ctx, capture)
    }
  }

  "DBInsertAction" should "emit one KO and call next once when requestName EL cannot be resolved" in {
    assertCrashContract("crash_insert") { (tc, capture) =>
      DBInsertAction(
        unresolvedName,
        _ => Success("some_table"),
        Seq("col"),
        capture,
        tc.ctx,
        Seq("col" -> (_ => Success("v": Any))),
      )
    }
  }

  "DBCallAction" should "emit one KO and call next once when requestName EL cannot be resolved" in {
    assertCrashContract("crash_call") { (tc, capture) =>
      DBCallAction(unresolvedName, _ => Success("some_proc"), capture, tc.ctx, Seq.empty, Seq.empty)
    }
  }

  "DBBatchAction" should "emit one KO and call next once when batchName EL cannot be resolved" in {
    assertCrashContract("crash_batch") { (tc, capture) =>
      DBBatchAction(
        unresolvedName,
        Seq(BatchInsertAction(_ => Success("some_table"), Columns("col"), Seq("col" -> (_ => Success("v": Any))))),
        capture,
        tc.ctx,
      )
    }
  }

  // ─── edge case: attribute present but not a String ───────────────────────────

  "DBQueryAction" should "not hang when the requestName attribute is present but not a String" in {
    // Gatling's String EL caster stringifies non-String attributes, so resolution succeeds
    // ("42") and the action completes normally — the contract under test is one `next`
    // invocation and no hang, whatever the resolved outcome.
    val stats   = new RecordingStatsEngine
    val tc      = buildRealTestContext("crash_type_mismatch", 2, config, stats)
    val capture = new CaptureAction()

    try {
      val action = DBQueryAction("#{num}".el[String], _ => Success("SELECT 1"), Seq.empty, Seq.empty, capture, tc.ctx)
      action ! freshSession(attributes = Map("num" -> 42))

      capture.awaitCapture() shouldBe true
      capture.invocationCount shouldBe 1
      stats.responses should have size 1
      stats.responses.head.requestName shouldBe "42"
    } finally {
      tc.close()
    }
  }
}
