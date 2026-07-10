package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.OK
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.NameGen
import org.galaxio.gatling.jdbc.db.SQL

import scala.util.{Failure, Success}

case class DBInsertAction(
    requestName: Expression[String],
    tableName: Expression[String],
    columns: Seq[String],
    next: Action,
    ctx: ScenarioContext,
    sessionValues: Seq[(String, Expression[Any])],
) extends ChainableAction with NameGen with ActionBase {
  override def name: String = genName("jdbcInsertAction")

  override def execute(session: Session): Unit =
    (for {
      rn       <- requestName(session)
      tName    <- tableName(session)
      iParams  <- resolveParams(session, sessionValues)
      sql       = SQL(s"INSERT INTO $tName (${columns.mkString(",")}) VALUES(${columns.map(s => s"{$s}").mkString(",")})")
                    .withParamsMap(iParams)
      startTime = now
    } yield dbClient.executeUpdate(sql.sql, sql.params) {
      case Success(_)         => executeNext(session, startTime, now, OK, next, rn, None, None)
      case Failure(exception) => reportError(session, startTime, rn, exception)
    })
      .onFailure(crashOnFailure(session, requestName))

  override def statsEngine: StatsEngine = ctx.coreComponents.statsEngine
}
