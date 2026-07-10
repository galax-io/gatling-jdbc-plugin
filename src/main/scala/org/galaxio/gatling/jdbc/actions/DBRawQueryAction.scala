package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.OK
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.NameGen
import org.galaxio.gatling.jdbc.db.SQL

import scala.util.{Failure, Success}

case class DBRawQueryAction(requestName: Expression[String], query: Expression[String], ctx: ScenarioContext, next: Action)
    extends ChainableAction with NameGen with ActionBase {
  override def name: String = genName("jdbcInsertAction")

  override def execute(session: Session): Unit =
    (for {
      resolvedName  <- requestName(session)
      resolvedQuery <- query(session)
      sql            = SQL(resolvedQuery)
      startTime      = now
    } yield dbClient.executeRaw(sql.q) {
      case Success(_)         => executeNext(session, startTime, now, OK, next, resolvedName, None, None)
      case Failure(exception) => reportError(session, startTime, resolvedName, exception)
    })
      .onFailure(crashOnFailure(session, requestName))

  override def statsEngine: StatsEngine = ctx.coreComponents.statsEngine
}
