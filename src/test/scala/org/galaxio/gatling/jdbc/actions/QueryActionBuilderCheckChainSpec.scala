package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.KO
import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder
import org.galaxio.gatling.jdbc.check.JdbcCheckSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression tests for issue #79: chaining `.check(a).check(b)` on the Scala query builder must append, not replace — every
  * declared check executes, in declaration order.
  *
  * Before the fix, `check` did `copy(checks = newChecks)`, silently dropping all previously chained checks: the builder-level
  * test and the behavior-level mutation test below both fail on the unfixed code.
  */
class QueryActionBuilderCheckChainSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture with JdbcCheckSupport {

  private def baseBuilder: QueryActionBuilder =
    QueryActionBuilder(_ => Success("chained-checks-request"), _ => Success("SELECT 1"), params = Seq.empty)

  // ─── builder-level: checks accumulate in declaration order ───────────────────

  "QueryActionBuilder.check" should "append chained checks instead of replacing them" in {
    val first  = simpleCheck(_.nonEmpty)
    val second = simpleCheck(_.isEmpty)
    val third  = simpleCheck(_ => true)

    baseBuilder.check(first).check(second).checks shouldBe Seq(first, second)
    baseBuilder.check(first).check(second, third).checks shouldBe Seq(first, second, third)
    baseBuilder.check(first).checks shouldBe Seq(first)
  }

  // ─── behavior-level: a failing first check is not silently dropped ───────────

  it should "execute an earlier failing check even when a later check passes" in {
    val stats   = new RecordingStatsEngine
    val tc      = buildRealTestContext("check_chain", 2, config, stats)
    val capture = new CaptureAction()

    try {
      val failingFirst  = simpleCheck(_ => false) // fails with "Jdbc check failed"
      val passingSecond = simpleCheck(_.nonEmpty)

      val action = baseBuilder.check(failingFirst).check(passingSecond).build(tc.ctx, capture)
      action ! freshSession()

      capture.awaitCapture() shouldBe true
      withClue("the first chained check must execute and fail the session: ") {
        capture.capturedSession.isFailed shouldBe true
      }

      val koResponses = stats.responses.filter(_.status == KO)
      koResponses should have size 1
      koResponses.head.responseCode shouldBe Some("Check ERROR")
      koResponses.head.message.getOrElse("") should include("Jdbc check failed")
    } finally {
      tc.close()
    }
  }

  it should "report a failure when any of three chained checks fails" in {
    val stats   = new RecordingStatsEngine
    val tc      = buildRealTestContext("check_chain_three", 2, config, stats)
    val capture = new CaptureAction()

    try {
      val passing     = simpleCheck(_.nonEmpty)
      val failingMid  = simpleCheck(_ => false)
      val passingLast = simpleCheck(_ => true)

      // The last registered check must PASS: under a replace-instead-of-append regression only
      // passingLast survives, no KO is reported, and this test fails without help from its siblings.
      val action = baseBuilder.check(passing).check(failingMid).check(passingLast).build(tc.ctx, capture)
      action ! freshSession()

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe true
      val koResponses = stats.responses.filter(_.status == KO)
      koResponses should have size 1
      koResponses.head.responseCode shouldBe Some("Check ERROR")
    } finally {
      tc.close()
    }
  }
}
