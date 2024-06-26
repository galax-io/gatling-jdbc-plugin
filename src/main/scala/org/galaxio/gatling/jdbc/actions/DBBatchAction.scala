package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation._
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.NameGen
import actions.{BatchAction, BatchInsertAction, BatchUpdateAction}
import io.gatling.core.stats.StatsEngine
import org.galaxio.gatling.jdbc.db._

final case class DBBatchAction(
    batchName: Expression[String],
    actions: Seq[BatchAction],
    next: Action,
    ctx: ScenarioContext,
) extends ChainableAction with NameGen with ActionBase {

  private implicit class TrSeq[+T](seq: Seq[T]) {
    def traverse[S](f: T => Validation[S]): Validation[Seq[S]] =
      seq.foldRight(Seq.empty[S].success)((i, r) => r.flatMap(s => f(i).map(s.prepended)))
  }

  override def name: String = genName("jdbcBatchAction")

  private def resolveParams(session: Session, values: Seq[(String, Expression[Any])]): Validation[Map[String, Any]] =
    values.traverse { case (k, v) => v(session).map((k, _)) }.map(_.toMap)

  private def resolveBatchAction(session: Session): PartialFunction[BatchAction, Validation[SqlWithParam]] = {

    case BatchUpdateAction(tableName, updateValues, None) =>
      for {
        tName   <- tableName(session)
        iParams <- resolveParams(session, updateValues)
        sql     <- SQL(s"UPDATE $tName SET ${iParams.map(c => s"${c._1} = {${c._1}}").mkString(",")}")
                     .withParamsMap(iParams)
                     .success
      } yield sql

    case BatchUpdateAction(tableName, updateValues, Some(whereExpression)) =>
      for {
        tName         <- tableName(session)
        iParams       <- resolveParams(session, updateValues)
        resolvedWhere <- whereExpression(session)
        sql           <- SQL(s"UPDATE $tName SET ${iParams.map(c => s"${c._1} = {${c._1}}").mkString(",")} WHERE $resolvedWhere")
                           .withParamsMap(iParams)
                           .success
      } yield sql

    case BatchInsertAction(tableName, columns, sessionValues) =>
      for {
        tName   <- tableName(session)
        iParams <- resolveParams(session, sessionValues)
        sql     <-
          SQL(s"INSERT INTO $tName (${columns.names.mkString(",")}) VALUES(${columns.names.map(s => s"{$s}").mkString(",")})")
            .withParamsMap(iParams)
            .success
      } yield sql
  }

  override protected def execute(session: Session): Unit = {
    (for {
      resolvedBatchName    <- batchName(session)
      sqlQueriesWithParams <- actions.traverse(resolveBatchAction(session))
      startTime            <- ctx.coreComponents.clock.nowMillis.success
    } yield dbClient
      .batch(sqlQueriesWithParams)(
        _ => executeNext(session, startTime, ctx.coreComponents.clock.nowMillis, OK, next, resolvedBatchName, None, None),
        e =>
          executeNext(
            session,
            startTime,
            ctx.coreComponents.clock.nowMillis,
            KO,
            next,
            resolvedBatchName,
            Some("ERROR"),
            Some(e.getMessage),
          ),
      ))
      .onFailure(m =>
        batchName(session).map { rn =>
          ctx.coreComponents.statsEngine.logRequestCrash(session.scenario, session.groups, rn, m)
          executeNext(
            session,
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
  }

  override def statsEngine: StatsEngine = ctx.coreComponents.statsEngine
}
