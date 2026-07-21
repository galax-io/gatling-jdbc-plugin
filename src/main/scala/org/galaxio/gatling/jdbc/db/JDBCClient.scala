package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import JDBCClient.Interpolator
import statements._

import java.sql.{CallableStatement, Connection, PreparedStatement, ResultSet, SQLFeatureNotSupportedException, Statement}
import java.util.concurrent.{ExecutorService, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal
import scala.util.{Try, Using}

object JDBCClient {
  object Interpolator {
    type ParamToIndexesMap = Map[String, List[Int]]
    case class InterpolatorCtx(
        queryString: String,
        paramName: String,
        paramIndex: Int,
        inCurlyBraces: Boolean,
        m: ParamToIndexesMap,
    )
    private val emptyCtx = InterpolatorCtx("", "", 0, inCurlyBraces = false, Map.empty)

    private[this] def putToCtx(ctx: Map[String, List[Int]], name: String, number: Int): Map[String, List[Int]] = {
      val numbers = ctx.getOrElse(name, List.empty[Int])
      ctx ++ Map((name, number :: numbers))
    }

    def interpolate(sql: String): InterpolatorCtx = sql.foldLeft(emptyCtx) {
      case (ic @ InterpolatorCtx(_, _, _, false, _), '{')    => ic.copy(inCurlyBraces = true)
      case (InterpolatorCtx(r, curName, n, true, ctx), '}')  =>
        InterpolatorCtx(s"$r ?", "", n + 1, inCurlyBraces = false, putToCtx(ctx, curName, n + 1))
      case (ic @ InterpolatorCtx(_, curName, _, true, _), c) => ic.copy(paramName = s"$curName$c")

      case (ic @ InterpolatorCtx(r, _, _, false, _), c) => ic.copy(queryString = s"$r$c")
    }
  }

  /** Auto-commit is a hard requirement (#88): Hikari rolls back dirty connections on checkin and connections are never pinned
    * to a virtual user, so a cross-action transaction can never commit — autoCommit=false only produces OK reports for writes
    * that silently vanish.
    */
  private[jdbc] val AutoCommitRequiredMessage: String =
    "gatling-jdbc requires auto-commit connections: the pool rolls back dirty connections after every action and " +
      "connections are not pinned to virtual users, so cross-action transactions can never commit. " +
      "Remove setAutoCommit(false) from the HikariConfig; for transactional work use the batch DSL " +
      "(one plugin-managed transaction per request) or a single rawSql action containing the whole BEGIN; ...; COMMIT block."

  def apply(pool: HikariDataSource, blockingPool: ExecutorService, queryTimeout: Option[FiniteDuration] = None): JDBCClient = {
    if (!pool.isAutoCommit) throw new IllegalArgumentException(AutoCommitRequiredMessage)
    new JDBCClient(pool, blockingPool, queryTimeout)
  }
}

class JDBCClient(pool: HikariDataSource, val blockingPool: ExecutorService, queryTimeout: Option[FiniteDuration] = None) {
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(blockingPool)

  private val queryTimeoutSeconds: Option[Int] = queryTimeout.map { d =>
    val secs = d.toSeconds
    if (secs == 0 && d > Duration.Zero) 1 // round up sub-second to 1s minimum
    else math.max(0, secs.toInt)
  }

  /** Streaming hint for the discard path — the drain holds at most one fetch batch in driver memory. */
  private val DiscardFetchSize = 1000

  /** Plugin-managed transaction: auto-commit disabled for the scope, restored on release. The primary failure must be THROWN
    * out of `op` (never returned as a value) so `Using.Manager` suppresses release failures onto it instead of replacing it
    * (#84).
    */
  private def withTransaction[T](op: (Connection, TransactionScope) => T): Try[T] =
    Using.Manager { use =>
      val conn  = use(pool.getConnection)
      val scope = use(new TransactionScope(conn))
      op(conn, scope)
    }

  private def withStatement[T](op: (Using.Manager, Statement) => T): Try[T] =
    Using.Manager { use =>
      val conn = use(pool.getConnection)
      val stmt = use(conn.createStatement)
      queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
      op(use, stmt)
    }

  private def withPreparedStatement[T](sql: String, params: Map[String, ParamVal])(
      op: (Using.Manager, PreparedStatement) => T,
  ): Try[T] =
    Using.Manager { use =>
      val conn            = use(pool.getConnection)
      val interpolatedCtx = Interpolator.interpolate(sql)
      val stmt            = use(conn.prepareStatement(interpolatedCtx.queryString))
      queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
      stmt.setParams(interpolatedCtx, params)
      op(use, stmt)
    }

  private def withCallableStatement[T](
      sql: String,
      inParams: Map[String, ParamVal],
      outParams: Map[String, Int],
  )(op: (Using.Manager, CallableStatement) => T): Try[T] = {
    Using.Manager { use =>
      val conn            = use(pool.getConnection())
      val interpolatedCtx = Interpolator.interpolate(sql)
      val stmt            = use(conn.prepareCall(s"${interpolatedCtx.queryString}"))
      queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
      stmt.setParams(interpolatedCtx, inParams, outParams)
      op(use, stmt)
    }
  }

  def executeRaw[U](sql: String)(consumer: Try[Boolean] => U): Future[U] = Future {
    val result = withStatement { (_, stmt) =>
      stmt.execute(sql)
    }
    consumer(result)
  }

  def executeSelect[U](
      sql: String,
      params: Seq[(String, ParamVal)],
  )(consumer: Try[List[Map[String, Any]]] => U): Future[U] =
    executeSelect(sql, params, None)(consumer)

  /** Materializing select with an optional row cap (#86). Exceeding the cap fails the operation — truncated check input would
    * be silent wrong data.
    */
  def executeSelect[U](
      sql: String,
      params: Seq[(String, ParamVal)],
      maxRows: Option[Int],
  )(consumer: Try[List[Map[String, Any]]] => U): Future[U] = Future {
    val result = withPreparedStatement(sql, params.toMap) { (use, stmt) =>
      maxRows.foreach(applyDriverRowGuard(stmt, _))
      val rs = use(stmt.executeQuery)
      maxRows match {
        case Some(cap) =>
          // cap + 1 detects overflow; at Int.MaxValue a List cannot exceed the cap anyway, so skip the +1 (Int overflow)
          val readLimit = if (cap == Int.MaxValue) cap else cap + 1
          val rows      = rs.iterator.take(readLimit).toList
          if (rows.sizeIs > cap) throw maxRowsExceeded(cap)
          rows
        case None      => rs.iterator.toList
      }
    }
    consumer(result)
  }

  /** Drains the full ResultSet without retaining rows and returns the row count (#86): the path for queries with no checks.
    *
    * The drain runs in a plugin-managed read transaction (auto-commit off for the scope) with a forward-only statement and a
    * fetch-size hint — PostgreSQL only streams under exactly these conditions; H2 is indifferent. Duplicate labels are still
    * rejected (#123) and the `maxRows` cap is still enforced: a cap is never silently ignored.
    */
  def executeSelectDiscard[U](
      sql: String,
      params: Seq[(String, ParamVal)],
      maxRows: Option[Int],
  )(consumer: Try[Long] => U): Future[U] = Future {
    val result = withTransaction { (conn, scope) =>
      try {
        val interpolatedCtx = Interpolator.interpolate(sql)
        val count           = Using.resource(
          conn.prepareStatement(interpolatedCtx.queryString, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY),
        ) { stmt =>
          queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
          stmt.setFetchSize(DiscardFetchSize)
          maxRows.foreach(applyDriverRowGuard(stmt, _))
          stmt.setParams(interpolatedCtx, params.toMap)
          Using.resource(stmt.executeQuery) { rs =>
            validatedResultLabels(rs)
            var count = 0L
            while (rs.next()) {
              count += 1
              maxRows.foreach(cap => if (count > cap) throw maxRowsExceeded(cap))
            }
            count
          }
        }
        conn.commit()
        count
      } catch {
        case NonFatal(primary) =>
          scope.rollbackAfter(primary)
          throw primary
      }
    }
    consumer(result)
  }

  private def maxRowsExceeded(cap: Int): IllegalStateException =
    new IllegalStateException(s"Query result exceeded the configured maxRows cap of $cap; failing instead of truncating")

  /** Best-effort driver-side transfer guard; correctness always comes from counting while reading. Guard math in Long so an
    * Int.MaxValue cap cannot overflow.
    */
  private def applyDriverRowGuard(stmt: Statement, cap: Int): Unit =
    try stmt.setLargeMaxRows(cap.toLong + 1)
    catch {
      case _: UnsupportedOperationException | _: SQLFeatureNotSupportedException =>
        if (cap < Int.MaxValue) stmt.setMaxRows(cap + 1)
    }

  def executeUpdate[U](sql: String, params: Seq[(String, ParamVal)])(
      consumer: Try[Int] => U,
  ): Future[U] = Future {
    val result = withPreparedStatement(sql, params.toMap) { (_, stmt) =>
      stmt.executeUpdate
    }
    consumer(result)
  }

  /** Execute a stored-procedure call and return the OUT parameter values surfaced by the database.
    *
    * If no OUT parameters are declared, the result will be an empty map. The update-count returned by `executeUpdate` is
    * intentionally not surfaced because stored procedures do not reliably report it across drivers; callers that need row
    * counts should use [[executeUpdate]] instead.
    *
    * @param sqlCall
    *   the CALL statement string (with named placeholders, e.g. `CALL my_proc({in1}, {out1})`)
    * @param params
    *   IN parameter bindings
    * @param outParams
    *   OUT parameter declarations: name → java.sql.Types constant
    * @param consumer
    *   a callback that will be called synchronously on the same thread the SQL call ran on to receive the result
    */
  def call[U](sqlCall: String, params: Seq[(String, ParamVal)], outParams: Seq[(String, Int)])(
      consumer: Try[Map[String, Any]] => U,
  ): Future[U] = Future {
    val result = withCallableStatement(sqlCall, params.toMap, outParams.toMap) { (_, stmt) =>
      stmt.executeUpdate()
      if (outParams.isEmpty) {
        Map.empty[String, Any]
      } else {
        val interpolated     = Interpolator.interpolate(sqlCall)
        // Collect only OUT-parameter name → index mappings from the interpolated index map
        val outParamIndexes  = interpolated.m.filter { case (name, _) => outParams.exists(_._1 == name) }
        val missingOutParams = outParams.map(_._1).filterNot(outParamIndexes.contains)
        if (missingOutParams.nonEmpty) {
          throw new IllegalArgumentException(
            s"OUT parameter(s) not found in SQL placeholders: ${missingOutParams.mkString(", ")}",
          )
        } else {
          stmt.getOutParams(outParamIndexes)
        }
      }
    }
    consumer(result)
  }

  /** Chunks `queries` into runs of adjacent elements sharing the same SQL text, preserving declared order (#82). */
  private def contiguousSqlRuns(queries: Seq[SqlWithParam]): Seq[(String, Seq[SqlWithParam])] =
    queries.foldRight(List.empty[(String, List[SqlWithParam])]) { (query, runs) =>
      runs match {
        case (sql, run) :: tail if sql == query.sql => (sql, query :: run) :: tail
        case _                                      => (query.sql, List(query)) :: runs
      }
    }

  def batch[U](queries: Seq[SqlWithParam])(consumer: Try[Array[Int]] => U): Future[U] = Future {
    val result = withTransaction { (conn, scope) =>
      try {
        val counts = contiguousSqlRuns(queries).foldLeft(Vector.empty[Int]) { case (resultCounts, (sql, group)) =>
          val interpolated = Interpolator.interpolate(sql)
          val batchCounts  = Using.resource(conn.prepareStatement(interpolated.queryString)) { stmt =>
            queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
            group.foreach { query =>
              stmt.setParams(interpolated, query.params.toMap)
              stmt.addBatch()
            }
            stmt.executeBatch()
          }
          resultCounts ++ batchCounts
        }
        conn.commit()
        counts.toArray
      } catch {
        case NonFatal(primary) =>
          scope.rollbackAfter(primary)
          throw primary
      }
    }
    consumer(result)
  }

  def close(): Unit = {
    pool.close()
    blockingPool.shutdown()
    blockingPool.awaitTermination(30, TimeUnit.SECONDS)
  }

  /** Auto-commit scope for a plugin-managed transaction. After a failed rollback the connection is in unknown state and
    * restoring auto-commit would COMMIT the open transaction (JDBC semantics on the false→true transition) — persisting partial
    * work under a KO report. Such a connection is evicted from the pool instead, and the restore is skipped (#84).
    */
  private class TransactionScope(connection: Connection) extends AutoCloseable {
    private val previousAutoCommit = connection.getAutoCommit
    private var broken             = false
    connection.setAutoCommit(false)

    /** Roll back after `primary` failed; a rollback failure is suppressed onto `primary`, never thrown. */
    def rollbackAfter(primary: Throwable): Unit =
      try connection.rollback()
      catch {
        case NonFatal(rollbackFailure) =>
          primary.addSuppressed(rollbackFailure)
          broken = true
          try pool.evictConnection(connection)
          catch { case NonFatal(evictFailure) => primary.addSuppressed(evictFailure) }
      }

    override def close(): Unit = if (!broken) connection.setAutoCommit(previousAutoCommit)
  }
}
