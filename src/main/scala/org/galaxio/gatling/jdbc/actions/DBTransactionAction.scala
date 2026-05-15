package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation._
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.NameGen

case class DBTransactionAction(
    requestName: Expression[String],
    statements: Seq[Expression[String]],
    ctx: ScenarioContext,
    next: Action,
) extends ChainableAction with NameGen with ActionBase {
  override def name: String = genName("jdbcTransactionAction")

  override def execute(session: Session): Unit = {
    val resolvedStmts = statements.foldLeft(Seq.empty[String].success) { (acc, expr) =>
      for {
        resolved <- acc
        stmt     <- expr(session)
      } yield resolved :+ stmt
    }

    (for {
      resolvedName <- requestName(session)
      stmts        <- resolvedStmts
      startTime    <- ctx.coreComponents.clock.nowMillis.success
    } yield dbClient
      .executeTransaction(stmts)(
        _ => executeNext(session, startTime, ctx.coreComponents.clock.nowMillis, OK, next, resolvedName, None, None),
        exception =>
          executeNext(
            session,
            startTime,
            ctx.coreComponents.clock.nowMillis,
            KO,
            next,
            resolvedName,
            Some("ERROR"),
            Some(exception.getMessage),
          ),
      )).onFailure { m =>
      val message =
        if (m.contains("No attribute named"))
          s"$m. Hint: ensure Gatling EL variable (e.g. #{varName}) is set in session before this action"
        else m
      requestName(session).map { rn =>
        ctx.coreComponents.statsEngine.logRequestCrash(session.scenario, session.groups, rn, message)
        executeNext(
          session,
          ctx.coreComponents.clock.nowMillis,
          ctx.coreComponents.clock.nowMillis,
          KO,
          next,
          rn,
          Some("ERROR"),
          Some(message),
        )
      }
    }
  }

  override def statsEngine: StatsEngine = ctx.coreComponents.statsEngine
}
