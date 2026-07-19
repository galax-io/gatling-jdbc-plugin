package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, Status}
import io.gatling.commons.validation._
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.structure.ScenarioContext
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.galaxio.gatling.jdbc.protocol.JdbcProtocol

trait ActionBase { self: ChainableAction =>
  val ctx: ScenarioContext

  private val jdbcComponents         = ctx.protocolComponentsRegistry.components(JdbcProtocol.jdbcProtocolKey)
  protected val dbClient: JDBCClient = jdbcComponents.client

  protected def now: Long = ctx.coreComponents.clock.nowMillis

  protected def resolveParams(session: Session, params: Seq[(String, Expression[Any])]): Validation[Map[String, Any]] =
    params.foldLeft(Map.empty[String, Any].success) { case (r, (k, v)) =>
      r.flatMap(m => v(session).map(rv => m + (k -> rv)))
    }

  /** KO callback for a failed JDBC execution: mark the session failed and log the response. */
  protected def reportError(session: Session, startTime: Long, requestName: String, exception: Throwable): Unit =
    executeNext(session.markAsFailed, startTime, now, KO, next, requestName, Some("ERROR"), Some(exception.getMessage))

  /** Validation crash handler: log a request crash and emit a KO response for the unresolved request.
    *
    * The request name is resolved at most once here; when resolution itself failed (the usual reason this handler runs), the
    * action's stable Gatling `name` is used as fallback so the KO is always emitted (#77).
    */
  protected def crashOnFailure(session: Session, requestName: Expression[String]): String => Unit =
    message => {
      val rn = requestName(session).toOption.getOrElse(name)
      ctx.coreComponents.statsEngine.logRequestCrash(session.scenario, session.groups, rn, message)
      executeNext(session.markAsFailed, now, now, KO, next, rn, Some("ERROR"), Some(message))
    }

  protected def executeNext(
      session: Session,
      sent: Long,
      received: Long,
      status: Status,
      next: Action,
      requestName: String,
      responseCode: Option[String],
      message: Option[String],
  ): Unit = {
    ctx.coreComponents.statsEngine.logResponse(
      session.scenario,
      session.groups,
      requestName,
      sent,
      received,
      status,
      responseCode,
      message,
    )
    next ! session.logGroupRequestTimings(sent, received)
  }
}
