package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, Status}
import io.gatling.commons.validation._
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.structure.ScenarioContext
import org.galaxio.gatling.jdbc.db.{JDBCClient, SqlIdentifier}
import org.galaxio.gatling.jdbc.protocol.{JdbcProtocol, Redaction}
import org.slf4j.LoggerFactory

trait ActionBase { self: ChainableAction =>
  val ctx: ScenarioContext

  private val jdbcComponents         = ctx.protocolComponentsRegistry.components(JdbcProtocol.jdbcProtocolKey)
  protected val dbClient: JDBCClient = jdbcComponents.client

  // #126: the raw throwable — message, suppressed detail, stack — goes here and only here. Enabling this logger at DEBUG is the
  // documented opt-in for full diagnostics; the stats/report path never carries raw driver text.
  private val rawErrorLog = LoggerFactory.getLogger("org.galaxio.gatling.jdbc.actions.ActionBase")

  protected def now: Long = ctx.coreComponents.clock.nowMillis

  /** Validates one identifier against the allowlist grammar (#124); failure flows to the crash KO path, no SQL is built.
    *
    * The rejected value is feeder-derived, so the failure surfaced to the crash/report path is value-free (#126, spec 005
    * FR-007): `safeMessage` carries the grammar hint without the input, while the full message (with the value) is logged at
    * DEBUG for an engineer who opts in.
    */
  protected def validIdentifier(value: String): Validation[String] =
    SqlIdentifier.validate(value) match {
      case Right(identifier) => identifier.success
      case Left(error)       =>
        if (rawErrorLog.isDebugEnabled) rawErrorLog.debug("Rejected invalid SQL identifier", error)
        error.safeMessage.failure
    }

  /** Validates a group of static identifiers (column names) in declaration order. */
  protected def validIdentifiers(values: Seq[String]): Validation[Seq[String]] =
    values.foldLeft(Seq.empty[String].success: Validation[Seq[String]]) { (acc, value) =>
      acc.flatMap(seen => validIdentifier(value).map(seen :+ _))
    }

  protected def resolveParams(session: Session, params: Seq[(String, Expression[Any])]): Validation[Map[String, Any]] =
    params.foldLeft(Map.empty[String, Any].success) { case (r, (k, v)) =>
      r.flatMap(m => v(session).map(rv => m + (k -> rv)))
    }

  /** KO callback for a failed JDBC execution: mark the session failed and log the response.
    *
    * The stats/report message is rebuilt from structured, value-free fields (#126) — class, SQLState, vendor code, plus a
    * class-only summary of any suppressed cleanup failures (#84) — so no feeder value can leak into a shared artifact. The full
    * raw throwable (message, suppressed detail, stack) is logged at DEBUG on `rawErrorLog`, the documented opt-in a triaging
    * engineer enables to see the original database error (spec 003 US3, now via the DEBUG channel rather than the report).
    */
  protected def reportError(session: Session, startTime: Long, requestName: String, exception: Throwable): Unit = {
    if (rawErrorLog.isDebugEnabled) rawErrorLog.debug(s"JDBC request '$requestName' failed", exception)
    executeNext(
      session.markAsFailed,
      startTime,
      now,
      KO,
      next,
      requestName,
      Some("ERROR"),
      Some(Redaction.koMessage(exception)),
    )
  }

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
