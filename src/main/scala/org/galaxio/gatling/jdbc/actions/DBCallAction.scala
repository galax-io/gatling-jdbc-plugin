package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation._
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.NameGen
import org.galaxio.gatling.jdbc.db.SQL

case class DBCallAction(
    requestName: Expression[String],
    procedureName: Expression[String],
    next: Action,
    ctx: ScenarioContext,
    sessionParams: Seq[(String, Expression[Any])],
    outParams: Seq[(String, Int)],
) extends ChainableAction with NameGen with ActionBase {

  override def name: String = genName("jdbcCallAction")

  private def makeCallString(procedureName: String, inParams: Map[String, Any], outParams: Map[String, Int]) =
    if (outParams.isEmpty) {
      s"CALL $procedureName (${inParams.keys.map(s => s"{$s}").mkString(",")})"
    } else {
      s"CALL $procedureName (${inParams.keys.map(s => s"{$s}").mkString(",")}, ${outParams.keys.map(s => s"$s =>{$s}").mkString(",")})"
    }

  override def execute(session: Session): Unit =
    (for {
      rn        <- requestName(session)
      pName     <- procedureName(session)
      pParams   <- sessionParams
                     .foldLeft(Map[String, Any]().success) { case (r, (k, v)) =>
                       r.flatMap(m => v(session).map(rv => m + (k -> rv)))
                     }
      sql       <- SQL(makeCallString(pName, pParams, outParams.toMap))
                     .withParamsMap(pParams)
                     .withOutParams(outParams)
                     .success
      startTime <- ctx.coreComponents.clock.nowMillis.success

    } yield dbClient
      .call(sql.sql, sql.params, sql.outParams)(
        outValues => {
          // Surface each OUT parameter value into the Gatling session under its parameter name so that
          // downstream actions and checks can reference them via session attributes (e.g. "#{outParamName}").
          val updatedSession = outValues.foldLeft(session) { case (s, (name, value)) => s.set(name, value) }
          executeNext(updatedSession, startTime, ctx.coreComponents.clock.nowMillis, OK, next, rn, None, None)
        },
        e =>
          executeNext(
            session.markAsFailed,
            startTime,
            ctx.coreComponents.clock.nowMillis,
            KO,
            next,
            rn,
            Some("ERROR"),
            Some(e.getMessage),
          ),
      ))
      .onFailure(m =>
        requestName(session).map { rn =>
          ctx.coreComponents.statsEngine.logRequestCrash(session.scenario, session.groups, rn, m)
          executeNext(
            session.markAsFailed,
            ctx.coreComponents.clock.nowMillis,
            ctx.coreComponents.clock.nowMillis,
            KO,
            next,
            rn,
            Some("ERROR"),
            Some(m),
          )
        },
      )

  override def statsEngine: StatsEngine = ctx.coreComponents.statsEngine
}
