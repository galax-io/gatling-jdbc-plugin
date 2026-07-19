package org.galaxio.gatling.jdbc.actions

import com.zaxxer.hikari.HikariConfig
import io.gatling.commons.util.Clock
import io.gatling.core.GatlingTestSupport
import io.gatling.core.action.Action
import io.gatling.core.actor.ActorSystem
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.protocol.{Protocol, ProtocolComponents, ProtocolKey}
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.netty.channel.{EventLoopGroup, nio}
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.galaxio.gatling.jdbc.db.testsupport.H2
import org.galaxio.gatling.jdbc.protocol.{JdbcComponents, JdbcProtocol}

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.collection.immutable.Map
import scala.collection.mutable

/** Shared test infrastructure for JDBC action specs. */
trait JdbcActionSpecSupport {

  protected def actorSystem: ActorSystem

  protected val fixedClock: Clock = new Clock {
    override def nowMillis: Long = System.currentTimeMillis()
  }

  protected val noOpExit: Action = new Action {
    override def name: String                    = "noop-exit"
    override def execute(session: Session): Unit = ()
  }

  protected case class TestContext(client: JDBCClient, ctx: ScenarioContext, eventLoopGroup: EventLoopGroup) {
    def close(): Unit = {
      client.close()
      eventLoopGroup.shutdownGracefully(0, 100, java.util.concurrent.TimeUnit.MILLISECONDS).sync()
    }
  }

  protected class CaptureAction extends Action {
    @volatile var capturedSession: Session = _
    private val latch                      = new CountDownLatch(1)
    private val invocations                = new java.util.concurrent.atomic.AtomicInteger(0)

    override def name: String                    = "capture"
    override def execute(session: Session): Unit = {
      capturedSession = session
      invocations.incrementAndGet()
      latch.countDown()
    }

    def awaitCapture(timeoutSeconds: Int = 5): Boolean = latch.await(timeoutSeconds.toLong, TimeUnit.SECONDS)
    def invocationCount: Int                           = invocations.get()
  }

  /** StatsEngine that records logResponse / logRequestCrash calls so specs can assert on emitted stats. */
  protected type RecordingStatsEngine = GatlingTestSupport.RecordingStatsEngine

  protected def buildTestContextWithClient(
      client: JDBCClient,
      config: GatlingConfiguration,
      statsEngine: StatsEngine = GatlingTestSupport.noOpStatsEngine,
  ): TestContext = {
    val jdbcComponents = JdbcComponents(client)
    val fakeProtocol   = new JdbcProtocol(new HikariConfig(), 1)

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
      statsEngine,
    )

    TestContext(client, scenarioCtx, elg)
  }

  protected def buildRealTestContext(
      dbName: String,
      poolSize: Int,
      config: GatlingConfiguration,
      statsEngine: StatsEngine = GatlingTestSupport.noOpStatsEngine,
  ): TestContext = {
    val ds           = H2.dataSource(dbName, poolSize)
    val blockingPool = Executors.newFixedThreadPool(poolSize)
    val client       = JDBCClient(ds, blockingPool)
    buildTestContextWithClient(client, config, statsEngine)
  }
}
