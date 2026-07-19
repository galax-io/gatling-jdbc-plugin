package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation.Success
import io.gatling.core.actor.ActorSystem
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session.Session
import io.netty.channel.DefaultEventLoop
import org.galaxio.gatling.jdbc.check.JdbcCheckSupport
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Map

/** Regression test for issue #78: a user-supplied check predicate that throws after a successful query must produce one KO
  * through the normal check-failure path, a failed session, and exactly one `next` invocation — never a hung virtual user.
  *
  * Before the fix, the exception escaped the executeSelect consumer into the Future's failure channel: no KO, no stats entry,
  * no `next` — this test fails by `awaitCapture` timeout on the unfixed code.
  */
class ThrowingCheckSpec
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

  "DBQueryAction" should "report one KO and call next once when a check predicate throws" in {
    val stats   = new RecordingStatsEngine
    val tc      = buildRealTestContext("jdbc:h2:mem:throwing_check;DB_CLOSE_DELAY=-1", 2, config, stats)
    val capture = new CaptureAction()

    try {
      val throwingCheck = simpleCheck(_ => throw new RuntimeException("boom from check"))

      val action = DBQueryAction(
        requestName = _ => Success("throwing-check-request"),
        sql = _ => Success("SELECT 1"),
        params = Seq.empty,
        checks = Seq(throwingCheck),
        next = capture,
        ctx = tc.ctx,
      )

      action ! freshSession

      withClue("next must be invoked despite the throwing check: ") {
        capture.awaitCapture() shouldBe true
      }
      capture.invocationCount shouldBe 1
      capture.capturedSession.isFailed shouldBe true

      val koResponses = stats.responses.filter(_.status == KO)
      withClue(s"exactly one KO stats entry expected, got: ${stats.responses}: ") {
        koResponses should have size 1
      }
      koResponses.head.requestName shouldBe "throwing-check-request"
      koResponses.head.responseCode shouldBe Some("Check ERROR")
      koResponses.head.message.getOrElse("") should include("boom from check")
    } finally {
      tc.close()
    }
  }

  it should "still report OK when checks pass after the guard is in place" in {
    val stats   = new RecordingStatsEngine
    val tc      = buildRealTestContext("jdbc:h2:mem:passing_check;DB_CLOSE_DELAY=-1", 2, config, stats)
    val capture = new CaptureAction()

    try {
      val action = DBQueryAction(
        requestName = _ => Success("passing-check-request"),
        sql = _ => Success("SELECT 1"),
        params = Seq.empty,
        checks = Seq(simpleCheck(_.nonEmpty)),
        next = capture,
        ctx = tc.ctx,
      )

      action ! freshSession

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      stats.responses.map(_.status) shouldBe List(OK)
    } finally {
      tc.close()
    }
  }
}
