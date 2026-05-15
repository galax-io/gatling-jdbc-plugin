package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK, Status}
import io.gatling.commons.util.Clock
import io.gatling.core.CoreComponents
import io.gatling.core.action.Action
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.protocol.ProtocolComponentsRegistry
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ActionBaseSpec extends AnyFlatSpec with Matchers {

  private val testClock: Clock = new Clock {
    override def nowMillis: Long = System.currentTimeMillis()
  }

  private val noOpStatsEngine: StatsEngine = new StatsEngine {
    override def start(): Unit                                                             = ()
    override def stop(controller: akka.actor.ActorRef, exception: Option[Exception]): Unit = ()
    override def logUserStart(scenario: String): Unit                                      = ()
    override def logUserEnd(scenario: String): Unit                                        = ()
    override def logResponse(
        scenario: String,
        groups: List[String],
        requestName: String,
        startTimestamp: Long,
        endTimestamp: Long,
        status: Status,
        responseCode: Option[String],
        message: Option[String],
    ): Unit                                                                                = ()
    override def logGroupEnd(
        scenario: String,
        groupBlock: io.gatling.core.session.GroupBlock,
        exitTimestamp: Long,
    ): Unit                                                                                = ()
    override def logRequestCrash(
        scenario: String,
        groups: List[String],
        requestName: String,
        error: String,
    ): Unit                                                                                = ()
  }

  private val coreComponents = new CoreComponents(
    actorSystem = null,
    eventLoopGroup = null,
    controller = null,
    throttler = None,
    statsEngine = noOpStatsEngine,
    clock = testClock,
    exit = null,
    configuration = GatlingConfiguration.loadForTest(),
  )

  private val scenarioCtx = new ScenarioContext(
    coreComponents = coreComponents,
    protocolComponentsRegistry = null.asInstanceOf[ProtocolComponentsRegistry],
    pauseType = io.gatling.core.pause.Disabled,
    throttled = false,
  )

  private def newSession(failed: Boolean = false): Session = {
    val session = new Session("test-scenario", 1L, Map.empty, OK, Nil, Session.NothingOnExit, null)
    if (failed) session.markAsFailed else session
  }

  private class CaptureAction extends Action {
    @volatile var capturedSession: Option[Session] = None
    override def name: String                      = "capture"
    override def !(session: Session): Unit         = capturedSession = Some(session)
    override def execute(session: Session): Unit   = capturedSession = Some(session)
  }

  private class TestActionBase(override val ctx: ScenarioContext) extends ActionBase {
    def callExecuteNext(
        session: Session,
        sent: Long,
        received: Long,
        status: Status,
        next: Action,
        requestName: String,
        responseCode: Option[String],
        message: Option[String],
    ): Unit = executeNext(session, sent, received, status, next, requestName, responseCode, message)
  }

  private val actionBase = new TestActionBase(scenarioCtx)

  "executeNext with OK status" should "not mark session as failed" in {
    val capture = new CaptureAction
    val session = newSession()

    actionBase.callExecuteNext(session, 100L, 200L, OK, capture, "test-request", None, None)

    capture.capturedSession shouldBe defined
    capture.capturedSession.get.isFailed shouldBe false
  }

  "executeNext with KO status" should "mark session as failed" in {
    val capture = new CaptureAction
    val session = newSession()

    actionBase.callExecuteNext(session, 100L, 200L, KO, capture, "test-request", Some("ERROR"), Some("SQL error"))

    capture.capturedSession shouldBe defined
    capture.capturedSession.get.isFailed shouldBe true
  }

  "executeNext with KO status" should "preserve existing session attributes" in {
    val capture = new CaptureAction
    val session = newSession().set("myKey", "myValue")

    actionBase.callExecuteNext(session, 100L, 200L, KO, capture, "test-request", Some("ERROR"), Some("fail"))

    val result = capture.capturedSession.get
    result.isFailed shouldBe true
    result.contains("myKey") shouldBe true
    result("myKey").as[String] shouldBe "myValue"
  }

  "executeNext with KO on already-failed session" should "remain failed" in {
    val capture = new CaptureAction
    val session = newSession(failed = true)

    actionBase.callExecuteNext(session, 100L, 200L, KO, capture, "test-request", Some("ERROR"), Some("fail"))

    capture.capturedSession.get.isFailed shouldBe true
  }

  "executeNext with OK on non-failed session" should "keep session successful" in {
    val capture = new CaptureAction
    val session = newSession()

    actionBase.callExecuteNext(session, 100L, 200L, OK, capture, "test-request", None, None)

    capture.capturedSession.get.isFailed shouldBe false
  }
}
