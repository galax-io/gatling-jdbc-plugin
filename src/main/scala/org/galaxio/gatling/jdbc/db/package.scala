package org.galaxio.gatling.jdbc

import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID
import scala.util.control.NonFatal

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

  /** Labels validated for uniqueness before any row is mapped — a duplicate would silently overwrite a value (#123). Runs on
    * every execution path, including the no-check discard path.
    */
  private[db] def validatedResultLabels(rs: ResultSet): IndexedSeq[String] = {
    val labels     = resultLabels(rs)
    val duplicated = labels.groupBy(identity).collect { case (label, occ) if occ.sizeIs > 1 => label }.toSeq.sorted
    if (duplicated.nonEmpty) throw new DuplicateColumnLabelException(duplicated)
    labels
  }

  private def record(resultSet: ResultSet, labels: IndexedSeq[String]): Map[String, Any] =
    labels.zipWithIndex.foldLeft(Map.empty[String, Any]) { case (m, (label, i)) =>
      m + (label -> detach(resultSet.getObject(i + 1)))
    }

  /** Copies driver-managed values while the ResultSet is still open and frees their locators (#87): a Blob/Clob/SQLXML/Array
    * handle stored in the session would be dead by check time — resources close before the consumer runs. Non-locator values
    * pass through unchanged.
    */
  private def detach(value: Any): Any = value match {
    case null                  => null
    case blob: java.sql.Blob   =>
      withFreed(blob.free()) {
        blob.getBytes(1, lengthAsInt("BLOB", blob.length()))
      }
    case clob: java.sql.Clob   => // NClob extends Clob — same detachment
      withFreed(clob.free()) {
        val length = lengthAsInt("CLOB", clob.length())
        if (length == 0) "" else clob.getSubString(1, length)
      }
    case xml: java.sql.SQLXML  =>
      withFreed(xml.free())(xml.getString)
    case array: java.sql.Array =>
      withFreed(array.free()) {
        array.getArray match {
          case elements: Array[_] => elements.toVector.map(detach)
          case other              => other
        }
      }
    case other                 => other
  }

  private def lengthAsInt(kind: String, length: Long): Int = {
    if (length > Int.MaxValue)
      throw new IllegalStateException(s"$kind of length $length exceeds the maximum supported size of Int.MaxValue")
    length.toInt
  }

  /** Runs `body`, then frees the locator in a finally-equivalent path: a free failure is suppressed onto the primary copy
    * failure (never replacing it) or thrown itself when the copy succeeded — the locator is never silently leaked.
    */
  private def withFreed[T](free: => Unit)(body: => T): T = {
    var primary: Throwable = null
    try body
    catch {
      case NonFatal(e) =>
        primary = e
        throw e
    } finally {
      try free
      catch {
        case NonFatal(freeFailure) =>
          if (primary != null) primary.addSuppressed(freeFailure) else throw freeFailure
      }
    }
  }

  implicit class ResultSetOps(val rs: ResultSet) extends AnyVal {

    def iterator: Iterator[Map[String, Any]] = {
      val labels = validatedResultLabels(rs)
      new Iterator[Map[String, Any]] {
        override def hasNext: Boolean = rs.next()

        override def next(): Map[String, Any] = record(rs, labels)
      }
    }

  }
}
