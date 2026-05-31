package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator.InterpolatorCtx

import java.sql.{CallableStatement, PreparedStatement, ResultSet, Statement, Timestamp}
import scala.concurrent.{ExecutionContext, Future}

object statements {

  trait StatementWrapper[F[_]] {
    def execute(sql: String): F[Boolean]
    def close: F[Unit]
    def addBatch(sql: String): F[Unit]
    def executeBatch: F[Array[Int]]
  }

  trait PreparedStatementWrapper[F[_]] {
    def executeQuery: F[ResultSet]
    def close: F[Unit]
    def executeUpdate: F[Int]
    def addBatch: F[Unit]
    def executeBatch: F[Array[Int]]
    def setInt(index: Int, value: Int): F[Unit]
    def setDouble(index: Int, value: Double): F[Unit]
    def setString(index: Int, value: String): F[Unit]
    def setLong(index: Int, value: Long): F[Unit]
    def setObject(index: Int, value: Object): F[Unit]
    def setBoolean(index: Int, value: Boolean): F[Unit]
    def setTimestamp(index: Int, value: java.sql.Timestamp): F[Unit]
    def setParams(interpolated: InterpolatorCtx, params: Map[String, ParamVal]): F[Unit]
  }

  trait CallableStatementWrapper[F[_]] {
    def close: F[Unit]
    def executeUpdate: F[Int]
    def setInt(index: Int, value: Int): F[Unit]
    def setDouble(index: Int, value: Double): F[Unit]
    def setString(index: Int, value: String): F[Unit]
    def setLong(index: Int, value: Long): F[Unit]
    def setBoolean(index: Int, value: Boolean): F[Unit]
    def setObject(index: Int, value: Object): F[Unit]
    def setTimestamp(index: Int, value: java.sql.Timestamp): F[Unit]
    def registerOutParameter(index: Int, sqlType: Int): F[Unit]
    def setParams(interpolated: InterpolatorCtx, inParams: Map[String, ParamVal], outParams: Map[String, Int]): Future[Unit]

    /** Read all registered OUT parameters after execution.
      *
      * @param outParams
      *   map of parameter name to its 1-based JDBC index (as built by the interpolator)
      * @return
      *   a map of parameter name to the value returned by the database
      */
    def getOutParams(outParams: Map[String, List[Int]]): F[Map[String, Any]]
  }

  private final class StatementWrapperImpl(stmt: Statement, queryTimeoutSeconds: Option[Int])(implicit ec: ExecutionContext)
      extends StatementWrapper[Future] {
    queryTimeoutSeconds.foreach(stmt.setQueryTimeout)

    override def execute(sql: String): Future[Boolean] = Future(stmt.execute(sql))

    override def close: Future[Unit] = Future(stmt.close())

    override def addBatch(sql: String): Future[Unit] = Future(stmt.addBatch(sql))

    override def executeBatch: Future[Array[Int]] = Future(stmt.executeBatch())
  }

  private final class PreparedStatementWrapperImpl(stmt: PreparedStatement, queryTimeoutSeconds: Option[Int])(implicit
      ec: ExecutionContext,
  ) extends PreparedStatementWrapper[Future] {
    queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
    override def executeQuery: Future[ResultSet] = Future(stmt.executeQuery())

    override def close: Future[Unit] = Future(stmt.close())

    override def executeUpdate: Future[Int] = Future(stmt.executeUpdate())

    override def addBatch: Future[Unit] = Future(stmt.addBatch())

    override def executeBatch: Future[Array[Int]] = Future(stmt.executeBatch())

    override def setDouble(index: Int, value: Double): Future[Unit] = Future(stmt.setDouble(index, value))

    override def setString(index: Int, value: String): Future[Unit] = Future(stmt.setString(index, value))

    override def setLong(index: Int, value: Long): Future[Unit] = Future(stmt.setLong(index, value))

    override def setObject(index: Int, value: Object): Future[Unit] = Future(stmt.setObject(index, value))

    override def setTimestamp(index: Int, value: Timestamp): Future[Unit] = Future(stmt.setTimestamp(index, value))

    override def setInt(index: Int, value: Int): Future[Unit] = Future(stmt.setInt(index, value))

    override def setBoolean(index: Int, value: Boolean): Future[Unit] = Future(stmt.setBoolean(index, value))

    override def setParams(interpolated: InterpolatorCtx, params: Map[String, ParamVal]): Future[Unit] = {
      if (params.isEmpty)
        Future.successful(())
      else
        interpolated.m.flatMap { case (name, indexes) =>
          params(name) match {
            case IntParam(v)     => indexes.map(this.setInt(_, v))
            case DoubleParam(v)  => indexes.map(this.setDouble(_, v))
            case StrParam(v)     => indexes.map(this.setString(_, v))
            case LongParam(v)    => indexes.map(this.setLong(_, v))
            case NullParam       => indexes.map(this.setObject(_, null))
            case DateParam(v)    => indexes.map(this.setTimestamp(_, Timestamp.valueOf(v)))
            case BooleanParam(v) => indexes.map(this.setBoolean(_, v))
            case UUIDParam(v)    => indexes.map(this.setString(_, v.toString))
          }
        }.reduce((f1, f2) => f1.flatMap(_ => f2))
    }
  }

  private final class CallableStatementWrapperImpl(stmt: CallableStatement, queryTimeoutSeconds: Option[Int])(implicit
      ec: ExecutionContext,
  ) extends CallableStatementWrapper[Future] {
    queryTimeoutSeconds.foreach(stmt.setQueryTimeout)
    override def close: Future[Unit] = Future(stmt.close())

    override def executeUpdate: Future[Int] = Future(stmt.executeUpdate())

    override def setDouble(index: Int, value: Double): Future[Unit] = Future(stmt.setDouble(index, value))

    override def setString(index: Int, value: String): Future[Unit] = Future(stmt.setString(index, value))

    override def setLong(index: Int, value: Long): Future[Unit] = Future(stmt.setLong(index, value))

    override def setObject(index: Int, value: Object): Future[Unit] = Future(stmt.setObject(index, value))

    override def setTimestamp(index: Int, value: Timestamp): Future[Unit] = Future(stmt.setTimestamp(index, value))

    override def setInt(index: Int, value: Int): Future[Unit] = Future(stmt.setInt(index, value))

    override def registerOutParameter(index: Int, sqlType: Int): Future[Unit] =
      Future(stmt.registerOutParameter(index, sqlType))

    override def setParams(
        interpolated: InterpolatorCtx,
        inParams: Map[String, ParamVal],
        outParams: Map[String, Int],
    ): Future[Unit] = {
      if (inParams.isEmpty && outParams.isEmpty)
        Future.successful(())
      else
        interpolated.m.flatMap {
          case (name, indexes) if outParams.contains(name) =>
            indexes.map(this.registerOutParameter(_, outParams(name)))
          case (name, indexes)                             =>
            inParams.get(name) match {
              case Some(IntParam(v))     => indexes.map(this.setInt(_, v))
              case Some(DoubleParam(v))  => indexes.map(this.setDouble(_, v))
              case Some(StrParam(v))     => indexes.map(this.setString(_, v))
              case Some(LongParam(v))    => indexes.map(this.setLong(_, v))
              case Some(NullParam)       => indexes.map(this.setObject(_, null))
              case Some(DateParam(v))    => indexes.map(this.setTimestamp(_, Timestamp.valueOf(v)))
              case Some(UUIDParam(v))    => indexes.map(this.setObject(_, v))
              case Some(BooleanParam(v)) => indexes.map(this.setBoolean(_, v))
              case None                  =>
                List(
                  Future.failed(
                    new IllegalArgumentException(
                      s"SQL placeholder '$name' has no corresponding parameter binding",
                    ),
                  ),
                )
            }
        }.reduce((f1, f2) => f1.flatMap(_ => f2))
    }

    override def setBoolean(index: Int, value: Boolean): Future[Unit] = Future(stmt.setBoolean(index, value))

    override def getOutParams(outParams: Map[String, List[Int]]): Future[Map[String, Any]] =
      Future(outParams.map { case (name, indexes) =>
        // Use the first index for each named parameter (names are unique in stored proc signatures)
        name -> stmt.getObject(indexes.head)
      })
  }

  def statement(statement: Statement, ec: ExecutionContext, queryTimeoutSeconds: Option[Int] = None): StatementWrapper[Future] =
    new StatementWrapperImpl(statement, queryTimeoutSeconds)(ec)

  def preparedStatement(
      preparedStatement: PreparedStatement,
      ec: ExecutionContext,
      queryTimeoutSeconds: Option[Int] = None,
  ): PreparedStatementWrapper[Future] =
    new PreparedStatementWrapperImpl(preparedStatement, queryTimeoutSeconds)(ec)

  def callableStatement(
      callableStatement: CallableStatement,
      ec: ExecutionContext,
      queryTimeoutSeconds: Option[Int] = None,
  ): CallableStatementWrapper[Future] =
    new CallableStatementWrapperImpl(callableStatement, queryTimeoutSeconds)(ec)
}
