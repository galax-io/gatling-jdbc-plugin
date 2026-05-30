package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import JDBCClient.Interpolator
import statements._
import statements.{CallableStatementWrapper, PreparedStatementWrapper, StatementWrapper}

import java.util.concurrent.ExecutorService
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}

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

class JDBCClient(pool: HikariDataSource, blockingPool: ExecutorService, queryTimeout: Option[FiniteDuration] = None) {
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(blockingPool)

  private val queryTimeoutSeconds: Option[Int] = queryTimeout.map { d =>
    val secs = d.toSeconds
    if (secs == 0 && d > Duration.Zero) 1 // round up sub-second to 1s minimum
    else math.max(0, secs.toInt)
  }

  private def connectionResource: ResourceFut[ConnectionWrapper[Future]] =
    ResourceFut.make(Future(ConnectionWrapper.Impl(pool.getConnection, ec)))(_.close)

  private def connectionForBatchResource: ResourceFut[ConnectionWrapper[Future]] =
    for {
      conn       <- connectionResource
      autoCommit <- ResourceFut.liftFuture(conn.getAutoCommit)
      _          <- ResourceFut.liftFuture(conn.setAutoCommit(false))
      _          <- ResourceFut.make(Future.successful(()))(_ => conn.setAutoCommit(autoCommit))
    } yield conn

  private def statementResource: ResourceFut[StatementWrapper[Future]] =
    for {
      conn <- connectionResource
      stmt <- ResourceFut.make(conn.createStatement.map(s => statement(s, ec, queryTimeoutSeconds)))(_.close)
    } yield stmt

  private def preparedStatementResource(
      sql: String,
      params: Map[String, ParamVal],
  ): ResourceFut[PreparedStatementWrapper[Future]] =
    for {
      conn            <- connectionResource
      interpolatedCtx <- ResourceFut.liftFuture(Future(Interpolator.interpolate(sql)))
      stmt            <- ResourceFut.make(
                           conn
                             .prepareStatement(interpolatedCtx.queryString)
                             .map(s => preparedStatement(s, ec, queryTimeoutSeconds)),
                         )(_.close)
      _               <- ResourceFut.liftFuture(stmt.setParams(interpolatedCtx, params))
    } yield stmt

  private def callableStatementResource(
      sql: String,
      inParams: Map[String, ParamVal],
      outParams: Map[String, Int],
  ): ResourceFut[CallableStatementWrapper[Future]] =
    for {
      conn            <- connectionResource
      interpolatedCtx <- ResourceFut.liftFuture(Future(Interpolator.interpolate(sql)))
      stmt            <- ResourceFut.make(
                           conn
                             .prepareCall(s"${interpolatedCtx.queryString}")
                             .map(s => callableStatement(s, ec, queryTimeoutSeconds)),
                         )(_.close)
      _               <- ResourceFut.liftFuture(stmt.setParams(interpolatedCtx, inParams, outParams))
    } yield stmt

  private def withCompletion[T, U](fut: Future[T])(s: T => U, f: Throwable => U): Unit = fut.onComplete {
    case Success(value)     => s(value)
    case Failure(exception) => f(exception)
  }

  def executeRaw[U](sql: String)(s: Boolean => U, f: Throwable => U): Unit =
    withCompletion(statementResource.use(_.execute(sql)))(s, f)

  def executeSelect[U](sql: String, params: Seq[(String, ParamVal)])(s: List[Map[String, Any]] => U, f: Throwable => U): Unit =
    withCompletion(preparedStatementResource(sql, params.toMap).use(_.executeQuery.map(_.iterator.toList)))(s, f)

  def executeUpdate[U](sqlQuery: String, params: Seq[(String, ParamVal)])(s: Int => U, f: Throwable => U): Unit =
    withCompletion(preparedStatementResource(sqlQuery, params.toMap).use(_.executeUpdate))(s, f)

  def call[U](sqlCall: String, params: Seq[(String, ParamVal)], outParams: Seq[(String, Int)])(
      s: Int => U,
      f: Throwable => U,
  ): Unit =
    withCompletion(callableStatementResource(sqlCall, params.toMap, outParams.toMap).use(_.executeUpdate))(s, f)

  def batch[U](queries: Seq[SqlWithParam])(s: Array[Int] => U, f: Throwable => U): Unit =
    withCompletion(
      connectionForBatchResource.use { conn =>
        queries
          .groupBy(_.sql)
          .toSeq
          .foldLeft(Future.successful(Vector.empty[Int])) { case (accFut, (sql, group)) =>
            accFut.flatMap { acc =>
              val interpolated = Interpolator.interpolate(sql)
              conn.prepareStatement(interpolated.queryString).flatMap { rawPs =>
                val ps = preparedStatement(rawPs, ec)
                group
                  .foldLeft(Future.successful(())) { (prev, query) =>
                    prev.flatMap(_ => ps.setParams(interpolated, query.params.toMap).flatMap(_ => ps.addBatch))
                  }
                  .flatMap(_ => ps.executeBatch)
                  .transformWith(result => ps.close.flatMap(_ => Future.fromTry(result)))
                  .map(counts => acc ++ counts)
              }
            }
          }
          .map(_.toArray)
          .transformWith {
            case Success(result)    => conn.commit.map(_ => result)
            case Failure(exception) => conn.rollback.flatMap(_ => Future.failed(exception))
          }
      },
    )(s, f)

  def close(): Unit = {
    pool.close()
    blockingPool.shutdown()
  }
}
