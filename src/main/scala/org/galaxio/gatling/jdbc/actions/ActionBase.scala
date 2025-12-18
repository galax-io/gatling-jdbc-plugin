package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.Status
import io.gatling.core.action.Action
import io.gatling.core.session.Session
import io.gatling.core.structure.ScenarioContext
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.galaxio.gatling.jdbc.protocol.JdbcProtocol

trait ActionBase {
  val ctx: ScenarioContext

  private val jdbcComponents         = ctx.protocolComponentsRegistry.components(JdbcProtocol.jdbcProtocolKey)
  protected val dbClient: JDBCClient = jdbcComponents.client

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
    // If the result status is KO, mark the session as failed so downstream actions see it
    // Also attach explicit attributes so Java DSL users can read it reliably
    val sessionToUse = if (status == io.gatling.commons.stats.KO)
      session.markAsFailed.set("jdbcFailed", true).set("successful", false).set("jdbcErrorMessage", message.getOrElse("Unknown error"))
    else session.set("jdbcFailed", false).set("successful", true).remove("jdbcErrorMessage").remove("HelterSkelter")
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
    next ! sessionToUse.logGroupRequestTimings(sent, received)
  }
}
