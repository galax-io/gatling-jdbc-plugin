package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation.Success
import io.gatling.core.actor.ActorSystem
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session.Session
import io.netty.channel.DefaultEventLoop
import org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder
import org.galaxio.gatling.jdbc.check.JdbcCheckSupport
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Map

/** Regression tests for issue #79: chaining `.check(a).check(b)` on the Scala query builder must append, not replace — every
  * declared check executes, in declaration order.
  *
  * Before the fix, `check` did `copy(checks = newChecks)`, silently dropping all previously chained checks: the builder-level
  * test and the behavior-level mutation test below both fail on the unfixed code.
  */
class QueryActionBuilderCheckChainSpec
    extends AnyFlatSpec with Matchers with BeforeAndAfterAll with JdbcActionSpecSupport with JdbcCheckSupport {

  private val eventLoop                    = new DefaultEventLoop()
  override val actorSystem                 = new ActorSystem()
  private val config: GatlingConfiguration = GatlingConfiguration.loadForTest()

  override protected def afterAll(): Unit = {
    eventLoop.shutdownGracefully()
    actorSystem.close()
    super.afterAll()
  }

  private def freshSession: Session =
    Session(
      scenario = "test",
      userId = 1L,
      attributes = Map.empty,
      baseStatus = OK,
      blockStack = Nil,
      onExit = Session.NothingOnExit,
      eventLoop = eventLoop,
    )

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
    val tc      = buildRealTestContext("jdbc:h2:mem:check_chain;DB_CLOSE_DELAY=-1", 2, config, stats)
    val capture = new CaptureAction()

    try {
      val failingFirst  = simpleCheck(_ => false) // fails with "Jdbc check failed"
      val passingSecond = simpleCheck(_.nonEmpty)

      val action = baseBuilder.check(failingFirst).check(passingSecond).build(tc.ctx, capture)
      action ! freshSession

      capture.awaitCapture() shouldBe true
      withClue("the first chained check must execute and fail the session: ") {
        capture.capturedSession.isFailed shouldBe true
      }

      val koResponses = stats.responses.filter(_.status == KO)
      koResponses should have size 1
      koResponses.head.responseCode shouldBe Some("Check ERROR")
    } finally {
      tc.close()
    }
  }

  it should "report a failure when any of three chained checks fails" in {
    val stats   = new RecordingStatsEngine
    val tc      = buildRealTestContext("jdbc:h2:mem:check_chain_three;DB_CLOSE_DELAY=-1", 2, config, stats)
    val capture = new CaptureAction()

    try {
      val passing     = simpleCheck(_.nonEmpty)
      val failingMid  = simpleCheck(_ => false)
      val failingLast = simpleCheck(_ => false)

      val action = baseBuilder.check(passing).check(failingMid).check(failingLast).build(tc.ctx, capture)
      action ! freshSession

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe true
      stats.responses.filter(_.status == KO) should have size 1
    } finally {
      tc.close()
    }
  }
}
