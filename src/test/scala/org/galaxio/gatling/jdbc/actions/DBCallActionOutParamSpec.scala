package org.galaxio.gatling.jdbc.actions

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.gatling.commons.stats.OK
import io.gatling.core.GatlingTestSupport
import io.gatling.core.action.Action
import io.gatling.core.actor.ActorSystem
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.protocol.{Protocol, ProtocolComponents, ProtocolKey}
import io.gatling.core.session.Session
import io.gatling.core.structure.ScenarioContext
import io.netty.channel.{DefaultEventLoop, EventLoopGroup, nio}
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.galaxio.gatling.jdbc.protocol.{JdbcComponents, JdbcProtocol}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Types
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.collection.immutable.Map
import scala.collection.mutable

/** Tests for stored procedure OUT parameter support (issue #28).
  *
  * Uses a stub JDBCClient subclass to inject pre-canned OUT parameter results into the action, which lets us verify the
  * session-update logic in DBCallAction without depending on a database that natively supports OUT parameters.
  *
  * Additionally tests that JDBCClient.call now accepts a Map[String, Any] => U success callback (API-contract check).
  */
class DBCallActionOutParamSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // ─── shared infrastructure ───────────────────────────────────────────────────

  private val eventLoop   = new DefaultEventLoop()
  private val actorSystem = new ActorSystem()

  override protected def afterAll(): Unit = {
    eventLoop.shutdownGracefully()
    actorSystem.close()
    super.afterAll()
  }

  // ─── Stub JDBCClient that bypasses JDBC entirely ─────────────────────────────

  /** A JDBCClient that, when `call` is invoked, immediately fires the success callback with a
    * pre-configured map of OUT parameter values.
    */
  private def stubClientWith(outResults: Map[String, Any]): JDBCClient = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:out_param_stub;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(1)
    val ds           = new HikariDataSource(cfg)
    val blockingPool = Executors.newFixedThreadPool(1)

    new JDBCClient(ds, blockingPool) {
      override def call[U](sqlCall: String, params: Seq[(String, org.galaxio.gatling.jdbc.db.ParamVal)], outParams: Seq[(String, Int)])(
          s: Map[String, Any] => U,
          f: Throwable => U,
      ): Unit = s(outResults)
    }
  }

  private val fixedClock = new io.gatling.commons.util.Clock {
    override def nowMillis: Long = System.currentTimeMillis()
  }

  private val noOpExit: Action = new Action {
    override def name: String                    = "noop-exit"
    override def execute(session: Session): Unit = ()
  }

  private case class TestContext(client: JDBCClient, ctx: ScenarioContext, eventLoopGroup: EventLoopGroup) {
    def close(): Unit = {
      client.close()
      eventLoopGroup.shutdownGracefully(0, 100, java.util.concurrent.TimeUnit.MILLISECONDS).sync()
    }
  }

  private def buildTestContext(outResults: Map[String, Any]): TestContext = {
    val client = stubClientWith(outResults)

    val jdbcComponents = JdbcComponents(client)
    val fakeProtocol   = new JdbcProtocol(new HikariConfig(), 1)

    val factoryCache: mutable.Map[ProtocolKey[_, _], Protocol => ProtocolComponents] =
      mutable.Map(JdbcProtocol.jdbcProtocolKey -> ((_: Protocol) => jdbcComponents))

    val protocols: Map[Class[_ <: Protocol], Protocol] =
      Map(classOf[JdbcProtocol] -> fakeProtocol)

    val elg    = new nio.NioEventLoopGroup(1)
    val config = GatlingConfiguration.loadForTest()

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

  private class CaptureAction extends Action {
    @volatile var capturedSession: Session = _
    private val latch                      = new CountDownLatch(1)

    override def name: String                    = "capture"
    override def execute(session: Session): Unit = { capturedSession = session; latch.countDown() }

    def awaitCapture(timeoutSeconds: Int = 5): Boolean = latch.await(timeoutSeconds.toLong, TimeUnit.SECONDS)
  }

  // ─── tests ───────────────────────────────────────────────────────────────────

  "DBCallAction" should "store OUT parameter values returned by the database into the Gatling session" in {
    val outResults = Map[String, Any]("myOut" -> Integer.valueOf(42), "anotherOut" -> "hello")
    val tc         = buildTestContext(outResults)
    val capture    = new CaptureAction()

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

      val action = DBCallAction(
        requestName = _ => io.gatling.commons.validation.Success("call-out-param-request"),
        procedureName = _ => io.gatling.commons.validation.Success("MY_PROC"),
        next = capture,
        ctx = tc.ctx,
        sessionParams = Seq.empty,
        outParams = Seq("myOut" -> Types.INTEGER, "anotherOut" -> Types.VARCHAR),
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      capture.capturedSession.attributes("myOut") shouldBe Integer.valueOf(42)
      capture.capturedSession.attributes("anotherOut") shouldBe "hello"
    } finally {
      tc.close()
    }
  }

  it should "not add any extra session attributes when no OUT parameters are declared" in {
    val tc      = buildTestContext(Map.empty)
    val capture = new CaptureAction()

    try {
      val session = Session(
        scenario = "test",
        userId = 2L,
        attributes = Map.empty,
        baseStatus = OK,
        blockStack = Nil,
        onExit = Session.NothingOnExit,
        eventLoop = eventLoop,
      )

      val action = DBCallAction(
        requestName = _ => io.gatling.commons.validation.Success("call-no-out-params"),
        procedureName = _ => io.gatling.commons.validation.Success("MY_PROC"),
        next = capture,
        ctx = tc.ctx,
        sessionParams = Seq.empty,
        outParams = Seq.empty,
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      capture.capturedSession.attributes shouldBe empty
    } finally {
      tc.close()
    }
  }

  it should "overwrite an existing session attribute when an OUT parameter name matches it" in {
    val outResults = Map[String, Any]("existingKey" -> Integer.valueOf(99))
    val tc         = buildTestContext(outResults)
    val capture    = new CaptureAction()

    try {
      val session = Session(
        scenario = "test",
        userId = 3L,
        attributes = Map("existingKey" -> "old-value"),
        baseStatus = OK,
        blockStack = Nil,
        onExit = Session.NothingOnExit,
        eventLoop = eventLoop,
      )

      val action = DBCallAction(
        requestName = _ => io.gatling.commons.validation.Success("call-overwrite"),
        procedureName = _ => io.gatling.commons.validation.Success("MY_PROC"),
        next = capture,
        ctx = tc.ctx,
        sessionParams = Seq.empty,
        outParams = Seq("existingKey" -> Types.INTEGER),
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      capture.capturedSession.attributes("existingKey") shouldBe Integer.valueOf(99)
    } finally {
      tc.close()
    }
  }
}
