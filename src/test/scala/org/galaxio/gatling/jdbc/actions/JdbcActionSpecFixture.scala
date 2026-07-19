package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.OK
import io.gatling.core.actor.ActorSystem
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session.Session
import io.netty.channel.DefaultEventLoop
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.collection.immutable.Map

/** Owns the Netty event loop and builds plain test sessions; tears the loop down in afterAll. For pure Session-level specs that
  * need no Gatling scenario context — action specs use [[JdbcActionSpecFixture]].
  */
trait SessionFixture extends BeforeAndAfterAll { self: Suite =>

  protected val eventLoop = new DefaultEventLoop()

  override protected def afterAll(): Unit = {
    eventLoop.shutdownGracefully()
    super.afterAll()
  }

  protected def freshSession(userId: Long = 1L, attributes: Map[String, Any] = Map.empty): Session =
    Session(
      scenario = "test",
      userId = userId,
      attributes = attributes,
      baseStatus = OK,
      blockStack = Nil,
      onExit = Session.NothingOnExit,
      eventLoop = eventLoop,
    )
}

/** Ready-to-mix fixture for action-level specs: [[SessionFixture]] plus the actor system and Gatling test config, torn down in
  * afterAll. Complements [[JdbcActionSpecSupport]] (which provides the H2 TestContext / capture / stats helpers).
  */
trait JdbcActionSpecFixture extends SessionFixture with JdbcActionSpecSupport { self: Suite =>

  override val actorSystem                   = new ActorSystem()
  protected val config: GatlingConfiguration = GatlingConfiguration.loadForTest()

  override protected def afterAll(): Unit = {
    actorSystem.close()
    super.afterAll()
  }
}
