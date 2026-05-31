package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.OK
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

  private def makeCallString(procedureName: String, inParams: Seq[(String, Any)], outParams: Seq[(String, Int)]) =
    if (outParams.isEmpty) {
      s"CALL $procedureName (${inParams.map(s => s"{${s._1}}").mkString(",")})"
    } else {
      s"CALL $procedureName (${inParams.map(s => s"{${s._1}}").mkString(",")}, ${outParams.map(s => s"${s._1} =>{${s._1}}").mkString(",")})"
    }

  override def execute(session: Session): Unit =
    (for {
      rn        <- requestName(session)
      pName     <- procedureName(session)
      pParams   <- sessionParams
                     .foldLeft(Seq.empty[(String, Any)].success) { case (r, (k, v)) =>
                       r.flatMap(seq => v(session).map(rv => seq :+ (k -> rv)))
                     }
      sql       <- SQL(makeCallString(pName, pParams, outParams))
                     .withParamsMap(pParams.toMap)
                     .withOutParams(outParams)
                     .success
      startTime <- now.success

    } yield dbClient
      .call(sql.sql, sql.params, sql.outParams)(
        outValues => {
          // Surface each OUT parameter value into the Gatling session under its parameter name so that
          // downstream actions and checks can reference them via session attributes (e.g. "#{outParamName}").
          val updatedSession = outValues.foldLeft(session) { case (s, (name, value)) => s.set(name, value) }
          executeNext(updatedSession, startTime, now, OK, next, rn, None, None)
        },
        e => reportError(session, startTime, rn, e),
      ))
      .onFailure(crashOnFailure(session, requestName))

  override def statsEngine: StatsEngine = ctx.coreComponents.statsEngine
}
