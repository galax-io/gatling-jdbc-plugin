package org.galaxio.gatling.jdbc.actions

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.gatling.commons.stats.OK
import io.gatling.commons.util.Clock
import io.gatling.core.GatlingTestSupport
import io.gatling.core.action.Action
import io.gatling.core.actor.ActorSystem
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.pause.Disabled
import io.gatling.core.protocol.{Protocol, ProtocolComponents, ProtocolComponentsRegistry, ProtocolKey}
import io.gatling.core.session.Session
import io.gatling.core.structure.ScenarioContext
import io.netty.channel.{DefaultEventLoop, EventLoopGroup, nio}
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.galaxio.gatling.jdbc.protocol.{JdbcComponents, JdbcProtocol}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.collection.immutable.{List, Map}
import scala.collection.mutable

/** Action-level regression test for issue #27: session.markAsFailed must be called on JDBC errors.
  *
  * Wires a real DBRawQueryAction and DBInsertAction against an H2 in-memory DB with deliberately bad SQL, captures the Session
  * forwarded to executeNext via a stub Action, and asserts session.isFailed == true. Removing `.markAsFailed` from the error
  * path in any JDBC action causes this test to fail.
  */
class ActionSessionFailureSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // ─── shared infrastructure ───────────────────────────────────────────────────

  private val eventLoop   = new DefaultEventLoop()
  private val actorSystem = new ActorSystem()

  override protected def afterAll(): Unit = {
    eventLoop.shutdownGracefully()
    actorSystem.close()
    super.afterAll()
  }

  private val config: GatlingConfiguration = GatlingConfiguration.loadForTest()

  private val fixedClock: Clock = new Clock {
    override def nowMillis: Long = System.currentTimeMillis()
  }

  private val noOpExit: Action = new Action {
    override def name: String                    = "noop-exit"
    override def execute(session: Session): Unit = ()
  }

  // ─── test context builder ────────────────────────────────────────────────────

  private case class TestContext(client: JDBCClient, ctx: ScenarioContext, eventLoopGroup: EventLoopGroup) {
    def close(): Unit = {
      client.close()
      eventLoopGroup.shutdownGracefully(0, 100, java.util.concurrent.TimeUnit.MILLISECONDS).sync()
    }
  }

  private def buildTestContext(): TestContext = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:action_failure_test;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(2)

    val ds           = new HikariDataSource(cfg)
    val blockingPool = Executors.newFixedThreadPool(2)
    val client       = JDBCClient(ds, blockingPool)

    val jdbcComponents = JdbcComponents(client)
    val fakeProtocol   = new JdbcProtocol(new HikariConfig(), 1)

    // The component factory must serve our pre-built JdbcComponents for JdbcProtocol.jdbcProtocolKey
    val factoryCache: mutable.Map[ProtocolKey[_, _], Protocol => ProtocolComponents] =
      mutable.Map(JdbcProtocol.jdbcProtocolKey -> ((_: Protocol) => jdbcComponents))

    val protocols: Map[Class[_ <: Protocol], Protocol] =
      Map(classOf[JdbcProtocol] -> fakeProtocol)

    val elg = new nio.NioEventLoopGroup(1)

    val scenarioCtx = GatlingTestSupport.makeScenarioContext(
      actorSystem,
      elg,
      fixedClock,
      noOpExit,
      config,
      factoryCache,
      protocols,
    )

    TestContext(client, scenarioCtx, elg)
  }

  // ─── stub next Action that captures the forwarded session ────────────────────

  private class CaptureAction extends Action {
    @volatile var capturedSession: Session = _
    private val latch                      = new CountDownLatch(1)

    override def name: String                    = "capture"
    override def execute(session: Session): Unit = { capturedSession = session; latch.countDown() }

    def awaitCapture(timeoutSeconds: Int = 5): Boolean = latch.await(timeoutSeconds.toLong, TimeUnit.SECONDS)
  }

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
