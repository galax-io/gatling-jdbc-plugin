package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import JDBCClient.Interpolator
import statements._
import statements.{CallableStatementWrapper, PreparedStatementWrapper, StatementWrapper}

import java.util.concurrent.ExecutorService
import scala.concurrent.{ExecutionContext, Future}
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

    private[this] def putToCtx(ctx: Map[String, List[Int]], name: String, number: Int): Map[String, List[Int]] = {
      val numbers = ctx.getOrElse(name, List.empty[Int])
      ctx ++ Map((name, number :: numbers))
    }

    def interpolate(sql: String): InterpolatorCtx = {
      val queryBuilder = new StringBuilder(sql.length)
      val nameBuilder  = new StringBuilder(16)
      var paramIndex   = 0
      var inBraces     = false
      var paramMap     = Map.empty[String, List[Int]]

      sql.foreach {
        case '{' if !inBraces =>
          inBraces = true
          nameBuilder.clear()
        case '}' if inBraces  =>
          paramIndex += 1
          queryBuilder.append(" ?")
          val name = nameBuilder.toString()
          paramMap = putToCtx(paramMap, name, paramIndex)
          inBraces = false
        case c if inBraces    =>
          nameBuilder.append(c)
        case c                =>
          queryBuilder.append(c)
      }

      InterpolatorCtx(queryBuilder.toString(), "", paramIndex, inCurlyBraces = false, paramMap)
    }
  }

  def apply(
      pool: HikariDataSource,
      blockingPool: ExecutorService,
      queryTimeoutSeconds: Option[Int] = None,
  ): JDBCClient = new JDBCClient(pool, blockingPool, queryTimeoutSeconds)
}

class JDBCClient(pool: HikariDataSource, blockingPool: ExecutorService, queryTimeoutSeconds: Option[Int]) {
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(blockingPool)

  private def connectionResource: ResourceFut[ConnectionWrapper[Future]] =
    ResourceFut.make(Future(ConnectionWrapper.Impl(pool.getConnection, ec)))(_.close)

  private def statementForBatchResource: ResourceFut[StatementWrapper[Future]] =
    for {
      conn       <- connectionResource
      autoCommit <- ResourceFut.liftFuture(conn.getAutoCommit)
      _          <- ResourceFut.liftFuture(conn.setAutoCommit(false))
      stmt       <- ResourceFut.make(conn.createStatement.map(statement(_, ec)))(s =>
                      for {
                        _ <- conn.commit
                        _ <- conn.setAutoCommit(autoCommit)
                        _ <- s.close
                      } yield (),
                    )
    } yield stmt

  private def transactionResource: ResourceFut[(ConnectionWrapper[Future], StatementWrapper[Future])] =
    for {
      conn       <- connectionResource
      autoCommit <- ResourceFut.liftFuture(conn.getAutoCommit)
      _          <- ResourceFut.liftFuture(conn.setAutoCommit(false))
      stmt       <- ResourceFut.make(conn.createStatement.map { s =>
                      queryTimeoutSeconds.foreach(s.setQueryTimeout)
                      statement(s, ec)
                    })(s =>
                      for {
                        _ <- conn.setAutoCommit(autoCommit)
                        _ <- s.close
                      } yield (),
                    )
    } yield (conn, stmt)

  private def statementResource: ResourceFut[StatementWrapper[Future]] =
    for {
      conn <- connectionResource
      stmt <- ResourceFut.make(conn.createStatement.map { s =>
                queryTimeoutSeconds.foreach(s.setQueryTimeout)
                statement(s, ec)
              })(_.close)
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
                             .map { ps =>
                               queryTimeoutSeconds.foreach(ps.setQueryTimeout)
                               preparedStatement(ps, ec)
                             },
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
                             .prepareCall(interpolatedCtx.queryString)
                             .map { cs =>
                               queryTimeoutSeconds.foreach(cs.setQueryTimeout)
                               callableStatement(cs, ec)
                             },
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

  def executeTransaction[U](statements: Seq[String])(s: Int => U, f: Throwable => U): Unit =
    if (statements.isEmpty) s(0)
    else
      withCompletion(transactionResource.use { case (conn, stmt) =>
        statements
          .foldLeft(Future.successful(0)) { (acc, sql) =>
            acc.flatMap(count => stmt.execute(sql).map(_ => count + 1))
          }
          .flatMap(count => conn.commit.map(_ => count))
          .recoverWith { case ex =>
            conn.rollback.flatMap(_ => Future.failed(ex))
          }
      })(s, f)

  def batch[U](queries: Seq[SqlWithParam])(s: Array[Int] => U, f: Throwable => U): Unit =
    if (queries.isEmpty) s(Array.empty[Int])
    else
      withCompletion(
        statementForBatchResource.use(stmt =>
          queries
            .map(query => stmt.addBatch(query.substituteParams))
            .reduce((f1, f2) => f1.flatMap(_ => f2))
            .flatMap(_ => stmt.executeBatch),
        ),
      )(s, f)

  def close(): Unit = {
    pool.close()
    blockingPool.shutdown()
  }
}
