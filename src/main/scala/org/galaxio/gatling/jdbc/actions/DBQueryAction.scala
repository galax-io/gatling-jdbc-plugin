package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.check.Check
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.NameGen
import org.galaxio.gatling.jdbc.db.SQL
import org.galaxio.gatling.jdbc.JdbcCheck

import java.util.{HashMap => JHashMap}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

case class DBQueryAction(
    requestName: Expression[String],
    sql: Expression[String],
    params: Seq[(String, Expression[Any])],
    checks: Seq[JdbcCheck],
    next: Action,
    ctx: ScenarioContext,
    maxRows: Option[Int] = None,
) extends ChainableAction with NameGen with ActionBase {

  override val name: String = genName("jdbcQueryAction")

  override def execute(session: Session): Unit = {
    (for {
      resolvedName   <- requestName(session)
      resolvedQuery  <- sql(session)
      resolvedParams <- resolveParams(session, params)
      parametrisedSql = SQL(resolvedQuery).withParamsMap(resolvedParams)
      startTime       = now
    } yield {
      // No check consumes rows → discard path: drain without retaining (#86). The maxRows cap applies on both paths.
      if (checks.isEmpty)
        dbClient.executeSelectDiscard(parametrisedSql.sql, parametrisedSql.params, maxRows) {
          case Success(_)         => executeNext(session, startTime, now, OK, next, resolvedName, None, None)
          case Failure(exception) => reportError(session, startTime, resolvedName, exception)
        }
      else
        dbClient.executeSelect(parametrisedSql.sql, parametrisedSql.params, maxRows) {
          case Success(result)    =>
            val received = now
            // A user check that throws would otherwise escape into the Future and never reach next (#78),
            // so route it through the same KO path as a regular check failure.
            try {
              val (newSession, checkError) = Check.check(result, session, checks.toList, new JHashMap[Any, Any]())
              checkError match {
                case Some(validation.Failure(errorMessage)) =>
                  executeNext(
                    newSession.markAsFailed,
                    startTime,
                    received,
                    KO,
                    next,
                    resolvedName,
                    Some("Check ERROR"),
                    Some(errorMessage),
                  )
                case _                                      => executeNext(newSession, startTime, received, OK, next, resolvedName, None, None)
              }
            } catch {
              case NonFatal(e) =>
                executeNext(
                  session.markAsFailed,
                  startTime,
                  received,
                  KO,
                  next,
                  resolvedName,
                  Some("Check ERROR"),
                  Some(e.getMessage),
                )
            }
          case Failure(exception) => reportError(session, startTime, resolvedName, exception)
        }
    })
      .onFailure(crashOnFailure(session, requestName))
  }

  override def statsEngine: StatsEngine = ctx.coreComponents.statsEngine
}
