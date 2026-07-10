package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator.InterpolatorCtx

import java.sql.{CallableStatement, PreparedStatement, ResultSet, Statement, Timestamp}
import scala.concurrent.{ExecutionContext, Future}

object statements {
  implicit class PreparedStatementOps(private val stmt: PreparedStatement) {
    def setParams(interpolated: InterpolatorCtx, params: Map[String, ParamVal]): Unit = {
      if (params.nonEmpty) {
        interpolated.m.foreach { case (name, indexes) =>
          params(name) match {
            case IntParam(v)     => indexes.foreach(stmt.setInt(_, v))
            case DoubleParam(v)  => indexes.foreach(stmt.setDouble(_, v))
            case StrParam(v)     => indexes.foreach(stmt.setString(_, v))
            case LongParam(v)    => indexes.foreach(stmt.setLong(_, v))
            case NullParam       => indexes.foreach(stmt.setObject(_, null))
            case DateParam(v)    => indexes.foreach(stmt.setTimestamp(_, Timestamp.valueOf(v)))
            case BooleanParam(v) => indexes.foreach(stmt.setBoolean(_, v))
            case UUIDParam(v)    => indexes.foreach(stmt.setString(_, v.toString))
          }
        }
      }
    }
  }

  implicit class CallableStatementOps(private val stmt: CallableStatement) {
    def setParams(
        interpolated: InterpolatorCtx,
        inParams: Map[String, ParamVal],
        outParams: Map[String, Int],
    ): Unit = {
      if (inParams.nonEmpty || outParams.nonEmpty)
        interpolated.m.foreach {
          case (name, indexes) if outParams.contains(name) =>
            indexes.foreach(stmt.registerOutParameter(_, outParams(name)))
          case (name, indexes)                             =>
            inParams.get(name) match {
              case Some(IntParam(v))     => indexes.foreach(stmt.setInt(_, v))
              case Some(DoubleParam(v))  => indexes.foreach(stmt.setDouble(_, v))
              case Some(StrParam(v))     => indexes.foreach(stmt.setString(_, v))
              case Some(LongParam(v))    => indexes.foreach(stmt.setLong(_, v))
              case Some(NullParam)       => indexes.foreach(stmt.setObject(_, null))
              case Some(DateParam(v))    => indexes.foreach(stmt.setTimestamp(_, Timestamp.valueOf(v)))
              case Some(UUIDParam(v))    => indexes.foreach(stmt.setObject(_, v))
              case Some(BooleanParam(v)) => indexes.foreach(stmt.setBoolean(_, v))
              case None                  =>
                throw new IllegalArgumentException(
                  s"SQL placeholder '$name' has no corresponding parameter binding",
                )

            }
        }
    }

    /** Read all registered OUT parameters after execution.
      *
      * @param outParams
      *   map of parameter name to its 1-based JDBC index (as built by the interpolator)
      * @return
      *   a map of parameter name to the value returned by the database
      */
    def getOutParams(outParams: Map[String, List[Int]]): Map[String, Any] =
      outParams.map { case (name, indexes) =>
        // Use the first index for each named parameter (names are unique in stored proc signatures)
        name -> stmt.getObject(indexes.head)
      }
  }
}
