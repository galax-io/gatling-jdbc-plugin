package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.OK
import io.gatling.core.actor.ActorSystem
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session.Session
import io.netty.channel.DefaultEventLoop
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Map

/** Action-level regression test for issue #27: session.markAsFailed must be called on JDBC errors.
  *
  * Wires a real DBRawQueryAction and DBInsertAction against an H2 in-memory DB with deliberately bad SQL, captures the Session
  * forwarded to executeNext via a stub Action, and asserts session.isFailed == true. Removing `.markAsFailed` from the error
  * path in any JDBC action causes this test to fail.
  */
class ActionSessionFailureSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with JdbcActionSpecSupport {

  // ─── shared infrastructure ───────────────────────────────────────────────────

  private val eventLoop                    = new DefaultEventLoop()
  override val actorSystem                 = new ActorSystem()
  private val config: GatlingConfiguration = GatlingConfiguration.loadForTest()

  override protected def afterAll(): Unit = {
    eventLoop.shutdownGracefully()
    actorSystem.close()
    super.afterAll()
  }

  // ─── test context builder ────────────────────────────────────────────────────

  private def buildTestContext(): TestContext =
    buildRealTestContext("jdbc:h2:mem:action_failure_test;DB_CLOSE_DELAY=-1", 2, config)

  // ─── tests ───────────────────────────────────────────────────────────────────

  "DBRawQueryAction" should "forward a failed session to next when SQL is invalid" in {
    val tc      = buildTestContext()
    val capture = new CaptureAction()

    try {
      val session = Session(
        scenario = "test",
        userId = 1L,
        attributes = Map.empty,
        baseStatus = OK,
        blockStack = Nil,
        onExit = Session.NothingOnExit,
        eventLoop = eventLoop,
      )

      val action = DBRawQueryAction(
        requestName = _ => io.gatling.commons.validation.Success("bad-sql-request"),
        query = _ => io.gatling.commons.validation.Success("THIS IS NOT VALID SQL AT ALL"),
        ctx = tc.ctx,
        next = capture,
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe true
    } finally {
      tc.close()
    }
  }

  "DBInsertAction" should "forward a failed session to next when the target table does not exist" in {
    val tc      = buildTestContext()
    val capture = new CaptureAction()

    try {
      val session = Session(
        scenario = "test",
        userId = 2L,
        attributes = Map("col" -> "value"),
        baseStatus = OK,
        blockStack = Nil,
        onExit = Session.NothingOnExit,
        eventLoop = eventLoop,
      )

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
    } finally {
      tc.close()
    }
  }
}
