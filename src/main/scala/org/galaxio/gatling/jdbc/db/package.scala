package org.galaxio.gatling.jdbc

import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

package object db {

  sealed trait ParamVal

  case class IntParam(v: Int)            extends ParamVal
  case class LongParam(v: Long)          extends ParamVal
  case object NullParam                  extends ParamVal
  case class DoubleParam(v: Double)      extends ParamVal
  case class StrParam(v: String)         extends ParamVal
  case class DateParam(v: LocalDateTime) extends ParamVal
  case class BooleanParam(v: Boolean)    extends ParamVal
  case class UUIDParam(v: UUID)          extends ParamVal

  case class SQL(q: String) {
    def withParams(params: (String, ParamVal)*): SqlWithParam = SqlWithParam(q, params)

    def withParamsMap(m: Map[String, Any]): SqlWithParam =
      withParams(m.map {
        case (k, v: Int)           => (k, IntParam(v))
        case (k, v: Long)          => (k, LongParam(v))
        case (k, v: Double)        => (k, DoubleParam(v))
        case (k, "NULL")           => (k, NullParam)
        case (k, v: String)        => (k, StrParam(v))
        case (k, v: LocalDateTime) => (k, DateParam(v))
        case (k, v: Boolean)       => (k, BooleanParam(v))
        case (k, v: UUID)          => (k, UUIDParam(v))
        case (k, null)             => (k, NullParam)
        case (k, v)                => (k, StrParam(v.toString))
      }.toSeq: _*)
  }

  case class SqlWithParam(sql: String, params: Seq[(String, ParamVal)], outParams: Seq[(String, Int)] = Seq.empty) {
    def withOutParams(ps: Seq[(String, Int)]): SqlWithParam = SqlWithParam(sql, params, ps)
  }

  /** Result-row keys are the column labels as written in the query (`AS` alias when present), verbatim — never the physical
    * column name and never case-normalized (#122). Read once per ResultSet, not per row.
    */
  private[db] def resultLabels(rs: ResultSet): IndexedSeq[String] = {
    val md = rs.getMetaData
    (1 to md.getColumnCount).map(md.getColumnLabel)
  }

  private def record(resultSet: ResultSet, labels: IndexedSeq[String]): Map[String, Any] =
    labels.zipWithIndex.foldLeft(Map.empty[String, Any]) { case (m, (label, i)) =>
      m + (label -> resultSet.getObject(i + 1))
    }

  implicit class ResultSetOps(val rs: ResultSet) extends AnyVal {

    def iterator: Iterator[Map[String, Any]] = {
      val labels = resultLabels(rs)
      new Iterator[Map[String, Any]] {
        override def hasNext: Boolean = rs.next()

        override def next(): Map[String, Any] = record(rs, labels)
      }
    }

  }
}
