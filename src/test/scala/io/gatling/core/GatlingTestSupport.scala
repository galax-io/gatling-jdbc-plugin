package io.gatling.core

import io.gatling.commons.util.Clock
import io.gatling.core.action.Action
import io.gatling.core.actor.{ActorRef, ActorSystem}
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.controller.Controller
import io.gatling.core.controller.throttle.Throttler
import io.gatling.core.pause.Disabled
import io.gatling.core.protocol.{Protocol, ProtocolComponents, ProtocolComponentsRegistry, ProtocolKey}
import io.gatling.core.session.{GroupBlock, Session}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.netty.channel.EventLoopGroup

import scala.collection.immutable.{List, Map}
import scala.collection.mutable

/** Package-level bridge that constructs Gatling internals from the io.gatling.core package, where Controller.Command is
  * accessible (private[gatling]).
  *
  * Only used in tests; not shipped in the production JAR.
  */
object GatlingTestSupport {

  val noOpStatsEngine: StatsEngine = new StatsEngine {
    override def start(): Unit                                                                                     = ()
    override def stop(controller: ActorRef[Controller.Command], exception: Option[Exception]): Unit                = ()
    override def logUserStart(scenario: String): Unit                                                              = ()
    override def logUserEnd(scenario: String): Unit                                                                = ()
    override def logResponse(
        scenario: String,
        groups: List[String],
        requestName: String,
        startTimestamp: Long,
        endTimestamp: Long,
        status: io.gatling.commons.stats.Status,
        responseCode: Option[String],
        message: Option[String],
    ): Unit                                                                                                        = ()
    override def logGroupEnd(scenario: String, group: GroupBlock, endTimestamp: Long): Unit                        = ()
    override def logRequestCrash(scenario: String, groups: List[String], requestName: String, error: String): Unit = ()
  }

  /** StatsEngine that records logResponse / logRequestCrash calls so specs can assert on emitted stats. */
  class RecordingStatsEngine extends StatsEngine {
    import RecordingStatsEngine._

    private val responseQueue = new java.util.concurrent.ConcurrentLinkedQueue[ResponseRecord]()
    private val crashQueue    = new java.util.concurrent.ConcurrentLinkedQueue[CrashRecord]()

    def responses: List[ResponseRecord] = {
      import scala.jdk.CollectionConverters.CollectionHasAsScala
      responseQueue.asScala.toList
    }
    def crashes: List[CrashRecord]      = {
      import scala.jdk.CollectionConverters.CollectionHasAsScala
      crashQueue.asScala.toList
    }

    override def start(): Unit                                                                                     = ()
    override def stop(controller: ActorRef[Controller.Command], exception: Option[Exception]): Unit                = ()
    override def logUserStart(scenario: String): Unit                                                              = ()
    override def logUserEnd(scenario: String): Unit                                                                = ()
    override def logResponse(
        scenario: String,
        groups: List[String],
        requestName: String,
        startTimestamp: Long,
        endTimestamp: Long,
        status: io.gatling.commons.stats.Status,
        responseCode: Option[String],
        message: Option[String],
    ): Unit                                                                                                        =
      responseQueue.add(ResponseRecord(requestName, status, responseCode, message))
    override def logGroupEnd(scenario: String, group: GroupBlock, endTimestamp: Long): Unit                        = ()
    override def logRequestCrash(scenario: String, groups: List[String], requestName: String, error: String): Unit =
      crashQueue.add(CrashRecord(requestName, error))
  }

  object RecordingStatsEngine {
    case class ResponseRecord(
        requestName: String,
        status: io.gatling.commons.stats.Status,
        responseCode: Option[String],
        message: Option[String],
    )
    case class CrashRecord(requestName: String, error: String)
  }

  private val noOpController: ActorRef[Controller.Command] = new ActorRef[Controller.Command] {
    override def !(message: Controller.Command): Unit                                                                    = ()
    override def name: String                                                                                            = "noop-controller"
    override def replyPromise[Reply](timeout: scala.concurrent.duration.FiniteDuration): scala.concurrent.Promise[Reply] =
      scala.concurrent.Promise()
  }

  def makeScenarioContext(
      actorSystem: ActorSystem,
      eventLoopGroup: EventLoopGroup,
      clock: Clock,
      exit: Action,
      config: GatlingConfiguration,
      componentFactoryCache: mutable.Map[ProtocolKey[_, _], Protocol => ProtocolComponents],
      protocols: Map[Class[_ <: Protocol], Protocol],
      statsEngine: StatsEngine = noOpStatsEngine,
  ): ScenarioContext = {
    val coreComponents = new CoreComponents(
      actorSystem,
      eventLoopGroup,
      noOpController,
      None: Option[ActorRef[Throttler.Command]],
      statsEngine,
      clock,
      exit,
      config,
    )
    val registry       = new ProtocolComponentsRegistry(
      coreComponents,
      protocols,
      componentFactoryCache,
      mutable.Map.empty,
    )
    new ScenarioContext(coreComponents, registry, Disabled, false)
  }
}
