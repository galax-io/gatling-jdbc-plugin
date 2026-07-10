package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import JDBCClient.Interpolator
import statements._

import java.sql.{CallableStatement, Connection, PreparedStatement, Statement}
import java.util.concurrent.{ExecutorService, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try, Using}

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

  def apply(pool: HikariDataSource, blockingPool: ExecutorService, queryTimeout: Option[FiniteDuration] = None): JDBCClient =
    new JDBCClient(pool, blockingPool, queryTimeout)
}

class JDBCClient(pool: HikariDataSource, val blockingPool: ExecutorService, queryTimeout: Option[FiniteDuration] = None) {
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(blockingPool)

  private val queryTimeoutSeconds: Option[Int] = queryTimeout.map { d =>
    val secs = d.toSeconds
    if (secs == 0 && d > Duration.Zero) 1 // round up sub-second to 1s minimum
    else math.max(0, secs.toInt)
  }

  private def connectionResource: ResourceFut[ConnectionWrapper[Future]] =
    ResourceFut.make(Future(ConnectionWrapper.Impl(pool.getConnection, ec)))(_.close)

  private def withConnectionForBatch[T](op: (Using.Manager, Connection) => T): Try[T] =
    Using.Manager { use =>
      val conn = use(pool.getConnection)
      use(new DisableAutoCommit(conn))
      op(use, conn)
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

  private def withCompletion[T, U](fut: Future[T])(s: T => U, f: Throwable => U): Unit = fut.onComplete {
    case Success(value)     => s(value)
    case Failure(exception) => f(exception)
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
  )(consumer: Try[List[Map[String, Any]]] => U): Future[U] = Future {
    val result = withPreparedStatement(sql, params.toMap) { (use, stmt) =>
      use(stmt.executeQuery).iterator.toList
    }
    consumer(result)
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
    * If no OUT parameters are declared the success callback receives an empty map. The update-count returned by `executeUpdate`
    * is intentionally not surfaced because stored procedures do not reliably report it across drivers; callers that need row
    * counts should use [[executeUpdate]] instead.
    *
    * @param sqlCall
    *   the CALL statement string (with named placeholders, e.g. `CALL my_proc({in1}, {out1})`)
    * @param params
    *   IN parameter bindings
    * @param outParams
    *   OUT parameter declarations: name → java.sql.Types constant
    * @param s
    *   success callback receiving a map of OUT parameter name → value
    * @param f
    *   failure callback
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

  def batch[U](queries: Seq[SqlWithParam])(consumer: Try[Array[Int]] => U): Future[U] = Future {
    val result = withConnectionForBatch { (_, conn) =>
      queries
        .groupBy(_.sql)
        .toSeq
        .foldLeft[Try[Vector[Int]]](Success(Vector.empty)) { case (resultTry, (sql, group)) =>
          resultTry.flatMap { resultCounts =>
            val interpolated = Interpolator.interpolate(sql)
            Using(conn.prepareStatement(interpolated.queryString)) { stmt =>
              queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
              group.foreach { query =>
                stmt.setParams(interpolated, query.params.toMap)
                stmt.addBatch()
              }
              stmt.executeBatch()
            }.map { batchCounts => resultCounts ++ batchCounts }
          }
        }
        .map(_.toArray)
        .transform(
          result => Try(conn.commit()).map(_ => result),
          exception => Try(conn.rollback()).flatMap(_ => Failure(exception)),
        )
    }.flatten
    consumer(result)
  }

  def close(): Unit = {
    pool.close()
    blockingPool.shutdown()
    blockingPool.awaitTermination(30, TimeUnit.SECONDS)
  }

  private class DisableAutoCommit(private val connection: Connection) extends AutoCloseable {
    private val previousAutoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    override def close(): Unit     = connection.setAutoCommit(previousAutoCommit)
  }
}
