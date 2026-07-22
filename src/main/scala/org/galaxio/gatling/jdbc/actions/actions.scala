package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.validation.SuccessWrapper
import io.gatling.core.action.Action
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.session.Expression
import io.gatling.core.structure.ScenarioContext
import org.galaxio.gatling.jdbc.JdbcCheck

object actions {
  case class DBBaseAction(requestName: Expression[String]) {
    def insertInto(tableName: Expression[String], columns: Columns): DBInsertActionValuesStep =
      DBInsertActionValuesStep(requestName, tableName, columns)
    def call(procedureName: Expression[String]): DBCallActionParamsStep                       = DBCallActionParamsStep(requestName, procedureName)

    def rawSql(queryString: Expression[String]): RawSqlActionBuilder = RawSqlActionBuilder(requestName, queryString)

    def queryP(sql: Expression[String]): QueryActionParamsStep = QueryActionParamsStep(requestName, sql)
    def query(sql: Expression[String]): QueryActionBuilder     = QueryActionBuilder(requestName, sql, params = Seq.empty)
    def batch(actions: BatchAction*): BatchActionBuilder       = BatchActionBuilder(requestName, actions)
  }

  final case class BatchInsertBaseAction(tableName: Expression[String], columns: Columns) {
    def values(values: (String, Expression[Any])*): BatchInsertAction = BatchInsertAction(tableName, columns, values)
  }

  final case class BatchUpdateBaseAction(tableName: Expression[String]) {
    def set(updateValues: (String, Expression[Any])*): BatchUpdateValuesStepAction =
      BatchUpdateValuesStepAction(tableName, updateValues)
  }

  final case class BatchUpdateValuesStepAction(tableName: Expression[String], updateValues: Seq[(String, Expression[Any])]) {

    /** The unsafe escape hatch (#125): the resolved text is interpolated into the UPDATE statement, so any session data inside
      * it becomes SQL text. Kept for compatibility; prefer the String overloads.
      */
    @deprecated(
      "Unsafe: the resolved text is interpolated into SQL, so session data can change the predicate. " +
        "Use where(\"col = 'x'\") for static clauses or where(\"col = {p}\", \"p\" -> value) to bind dynamic values as data.",
      "1.5.0",
    )
    def where(whereExpression: Expression[String]): BatchUpdateAction = {
      BatchUpdateAction(tableName, updateValues, Some(whereExpression))
    }

    /** Static, author-fixed WHERE clause (#125). Gatling EL is rejected at construction time — a `#{…}` inside the clause would
      * resolve session data into statement text. `{name}` placeholders referencing SET values keep their pre-1.5.0 binding.
      */
    def where(whereClause: String): BatchUpdateAction = {
      WhereClauseGuard.requireNoGatlingEl(whereClause)
      BatchUpdateAction(tableName, updateValues, Some(_ => whereClause.success))
    }

    /** Static WHERE clause with dynamic values bound as data (#125): `{name}` placeholders in the clause are bound from
      * `params` (or from SET values) through the prepared-statement machinery — a value can only ever match rows, never change
      * the predicate.
      */
    def where(whereClause: String, params: (String, Expression[Any])*): BatchParameterizedUpdateAction = {
      WhereClauseGuard.requireNoGatlingEl(whereClause)
      WhereClauseGuard.requireParamsBindable(whereClause, params.map(_._1), updateValues.map(_._1))
      BatchParameterizedUpdateAction(tableName, updateValues, whereClause, params)
    }

    val all: BatchUpdateAction = BatchUpdateAction(tableName, updateValues)
  }

  /** Construction-time validation for the safe `where` overloads (#125). Failing fast here keeps unsafe predicates from ever
    * reaching a running simulation (spec 005 FR-004/FR-010).
    */
  private object WhereClauseGuard {
    private val Placeholder = "\\{([^{}]*)\\}".r

    def requireNoGatlingEl(whereClause: String): Unit =
      require(
        !whereClause.contains("#{"),
        "Gatling EL (\"#{...}\") inside a where(...) clause would turn session data into SQL text (injection risk). " +
          "Bind dynamic values as data instead: where(\"col = {p}\", \"p\" -> \"#{sessionVar}\")",
      )

    def requireParamsBindable(whereClause: String, paramNames: Seq[String], setNames: Seq[String]): Unit = {
      val placeholders = Placeholder.findAllMatchIn(whereClause).map(_.group(1)).toSet
      val duplicates   = paramNames.diff(paramNames.distinct).distinct
      require(duplicates.isEmpty, s"Duplicate where(...) parameter name(s): ${duplicates.mkString(", ")}")
      val collisions   = paramNames.filter(setNames.contains)
      require(
        collisions.isEmpty,
        s"where(...) parameter name(s) ${collisions.mkString(", ")} collide with SET value names; rename the where parameter",
      )
      val unbound      = paramNames.filterNot(placeholders.contains)
      require(
        unbound.isEmpty,
        s"where(...) parameter name(s) ${unbound.mkString(", ")} have no matching {placeholder} in the clause '$whereClause'",
      )
      // every placeholder must resolve to a where-param or a SET value; an unresolved one would only fail at bind time per
      // request (spec 005 FR-010 wants build-time rejection)
      val bindable     = paramNames.toSet ++ setNames.toSet
      val unresolved   = placeholders.diff(bindable)
      require(
        unresolved.isEmpty,
        s"where(...) placeholder(s) ${unresolved.toSeq.sorted.mkString(", ")} have no matching parameter or SET value " +
          s"in the clause '$whereClause'",
      )
    }
  }

  case class QueryActionParamsStep(requestName: Expression[String], sql: Expression[String]) {
    def params(ps: (String, Expression[Any])*): QueryActionBuilder = QueryActionBuilder(requestName, sql, ps)
  }

  case class QueryActionBuilder(
      requestName: Expression[String],
      sql: Expression[String],
      params: Seq[(String, Expression[Any])],
      checks: Seq[JdbcCheck] = Seq.empty,
      maxRows: Option[Int] = None,
  ) extends ActionBuilder {

    /** Appends `newChecks` to the already-declared checks (#79); checks execute in declaration order. */
    def check(newChecks: JdbcCheck*): QueryActionBuilder = this.copy(checks = checks ++ newChecks)

    /** Caps the rows read for this query (#86); a result exceeding the cap fails the request instead of truncating. */
    def maxRows(n: Int): QueryActionBuilder = {
      require(n > 0, "maxRows must be positive")
      this.copy(maxRows = Some(n))
    }

    override def build(ctx: ScenarioContext, next: Action): Action = DBQueryAction(
      requestName,
      sql,
      params,
      checks,
      next,
      ctx,
      maxRows,
    )
  }

  case class RawSqlActionBuilder(requestName: Expression[String], query: Expression[String]) extends ActionBuilder {
    override def build(ctx: ScenarioContext, next: Action): Action = DBRawQueryAction(requestName, query, ctx, next)
  }

  case class Columns(names: String*)

  case class DBCallActionParamsStep(requestName: Expression[String], procedureName: Expression[String]) {
    def params(ps: (String, Expression[Any])*): DBCallActionBuilder = DBCallActionBuilder(requestName, procedureName, ps)
  }

  case class DBCallActionBuilder(
      requestName: Expression[String],
      procedureName: Expression[String],
      sessionParams: Seq[(String, Expression[Any])],
      outParams: Seq[(String, Int)] = Seq.empty,
  ) extends ActionBuilder {
    override def build(ctx: ScenarioContext, next: Action): Action =
      DBCallAction(requestName, procedureName, next, ctx, sessionParams, outParams)

    def outParams(ps: (String, Int)*): DBCallActionBuilder = this.copy(outParams = ps)
  }

  case class DBInsertActionValuesStep(requestName: Expression[String], tableName: Expression[String], columns: Columns) {
    def values(values: (String, Expression[Any])*): DBInsertActionBuilder =
      DBInsertActionBuilder(requestName, tableName, columns, values)
  }

  case class DBInsertActionBuilder(
      requestName: Expression[String],
      tableName: Expression[String],
      columns: Columns,
      sessionValues: Seq[(String, Expression[Any])] = Seq.empty,
  ) extends ActionBuilder {
    override def build(ctx: ScenarioContext, next: Action): Action =
      DBInsertAction(requestName, tableName, columns.names, next, ctx, sessionValues)
  }

  sealed trait BatchAction
  final case class BatchInsertAction(
      tableName: Expression[String],
      columns: Columns,
      sessionValues: Seq[(String, Expression[Any])],
  ) extends BatchAction

  final case class BatchUpdateAction(
      tableName: Expression[String],
      updateValues: Seq[(String, Expression[Any])],
      where: Option[Expression[String]] = None,
  ) extends BatchAction

  /** Parameterized batch update (#125): a static clause whose `{name}` placeholders are bound from `whereParams` (or SET
    * values) as prepared-statement data. A separate carrier — extending [[BatchUpdateAction]] with a field would break its
    * published constructor/`copy`/`unapply` shapes.
    */
  final case class BatchParameterizedUpdateAction(
      tableName: Expression[String],
      updateValues: Seq[(String, Expression[Any])],
      whereClause: String,
      whereParams: Seq[(String, Expression[Any])],
  ) extends BatchAction

  final case class BatchActionBuilder(batchName: Expression[String], actions: Seq[BatchAction]) extends ActionBuilder {
    override def build(ctx: ScenarioContext, next: Action): Action = DBBatchAction(batchName, actions, next, ctx)
  }
}
