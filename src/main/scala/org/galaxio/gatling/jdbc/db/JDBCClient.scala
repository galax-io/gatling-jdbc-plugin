package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import JDBCClient.Interpolator
import statements._

import java.sql.{CallableStatement, Connection, PreparedStatement, ResultSet, Statement}
import java.util.concurrent.{ExecutorService, TimeUnit}
import scala.collection.immutable.AbstractMap
import scala.collection.mutable.ArrayBuffer
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

    /** Replaces each `{name}` outside literals and comments with ` ?`, in one pass over `sql` (see `Scanner`). */
    def interpolate(sql: String): InterpolatorCtx = new Scanner(sql).run()

    // what the scanner is reading
    private final val Normal         = 0
    private final val InBraces       = 1 // a placeholder name
    private final val InQuote        = 2 // '…'
    private final val InIdentifier   = 3 // "…"
    private final val InEscape       = 4 // E'…'
    private final val AfterBackslash = 5 // in E'…', right after a backslash
    private final val AfterQuote     = 6 // in E'…', right after a quote: it closes, unless a second quote follows
    private final val InLineComment  = 7
    private final val InBlockComment = 8

    // dialect quoting, which counts only once its closing marker comes
    private final val Backtick = 0
    private final val Bracket  = 1
    private final val Dollar   = 2
    private final val Slashes  = 3
    private final val QQuote   = 4
    private final val Kinds    = 5

    /** Replaces each `{name}` with ` ?` in one pass over the SQL, a character at a time.
      *
      * String literals `'…'` (in `E'…'` a backslash escapes), quoted identifiers `"…"`, `--` comments up to the end of the line
      * and block comments up to their first closing marker are copied as written, so a brace inside them is never a
      * placeholder.
      *
      * Dialect quoting is copied as written once its closing marker comes; until then the SQL is read as if it were not there,
      * and what was read of it is replaced when it closes. Backtick and bracket identifiers and `//` comments close only before
      * an opening brace, a dollar-quoted string with a named tag only before an opening brace and outside a literal, a `q'…'`
      * string only outside a literal.
      */
    private final class Scanner(sql: String) {
      private val length    = sql.length
      private val query     = new java.lang.StringBuilder(length)
      private val name      = new java.lang.StringBuilder
      private val names     = new ArrayBuffer[String] // the name of each placeholder, in order
      private var state     = Normal
      private var blockBody = 0

      // the dialect quoting open now, at most one of each kind: where it opened (-1 when none), where its body starts, and
      // the query and the placeholders as they stood before it, to go back to when it closes
      private val opened     = Array.fill(Kinds)(-1)
      private val bodyFrom   = new Array[Int](Kinds)
      private val queryAt    = new Array[Int](Kinds)
      private val namesAt    = new Array[Int](Kinds)
      private var openCount  = 0
      private var dollarTag  = ""
      private var qClose     = ' '
      private var closing    = -1
      private var closingEnd = 0

      def run(): InterpolatorCtx = {
        var i = 0
        while (i < length) {
          val c = sql.charAt(i)
          if (openCount > 0) findClosing(i, c)
          if (closing >= 0) i = close()
          else {
            if (c == '{') dropBraceFree()
            i = step(i, c)
          }
        }
        InterpolatorCtx(query.toString, name.toString, names.length, state == InBraces, new Placeholders(names))
      }

      private def charAt(at: Int, c: Char): Boolean = at >= 0 && at < length && sql.charAt(at) == c

      private def identAt(at: Int): Boolean = at >= 0 && {
        val c = sql.charAt(at)
        c.isLetterOrDigit || c == '_' || c == '$' || c >= '\u0080'
      }

      private def copy(i: Int, width: Int): Int = {
        query.append(sql, i, i + width)
        i + width
      }

      private def enter(next: Int, i: Int, width: Int): Int = {
        state = next
        copy(i, width)
      }

      /** Reads `c` at `i` in the current state and returns where to read next. */
      private def step(i: Int, c: Char): Int = state match {
        case InBraces                                                                            =>
          if (c != '}') name.append(c)
          else {
            names += name.toString
            name.setLength(0)
            query.append(" ?")
            state = Normal
          }
          i + 1
        case InQuote | InIdentifier | InLineComment | InBlockComment | InEscape | AfterBackslash =>
          state = next(i, c)
          copy(i, 1)
        case AfterQuote if c == '\''                                                             => enter(InEscape, i, 1)
        case _                                                                                   =>
          state = Normal
          normal(i, c)
      }

      /** The state after `c` at `i`, inside a literal or a comment. */
      private def next(i: Int, c: Char): Int = state match {
        case InQuote        => if (c == '\'') Normal else InQuote
        case InIdentifier   => if (c == '"') Normal else InIdentifier
        case InLineComment  => if (c == '\n' || c == '\r') Normal else InLineComment
        case InBlockComment => if (c == '/' && i > blockBody && sql.charAt(i - 1) == '*') Normal else InBlockComment
        case InEscape       => if (c == '\\') AfterBackslash else if (c == '\'') AfterQuote else InEscape
        case _              => InEscape
      }

      private def normal(i: Int, c: Char): Int = c match {
        case '{'                                                 =>
          state = InBraces
          i + 1
        case '\''                                                => enter(InQuote, i, 1)
        case '"'                                                 => enter(InIdentifier, i, 1)
        case '-' if charAt(i + 1, '-')                           => enter(InLineComment, i, 2)
        case '/' if charAt(i + 1, '*')                           =>
          blockBody = i + 2
          enter(InBlockComment, i, 2)
        case 'E' | 'e' if charAt(i + 1, '\'') && !identAt(i - 1) => enter(InEscape, i, 2)
        case '/' if charAt(i + 1, '/')                           =>
          if (opened(Slashes) < 0) open(Slashes, i, i + 2)
          copy(i, 2)
        case '$' if opened(Dollar) < 0 && !identAt(i - 1)        => dollar(i)
        case _                                                   =>
          openAt(i, c)
          copy(i, 1)
      }

      private def open(kind: Int, at: Int, body: Int): Unit = {
        opened(kind) = at
        bodyFrom(kind) = body
        queryAt(kind) = query.length
        namesAt(kind) = names.length
        openCount += 1
      }

      private def drop(kind: Int): Unit = if (opened(kind) >= 0) {
        opened(kind) = -1
        openCount -= 1
      }

      /** An opening brace means the dialect quoting that must hold none was no quoting. */
      private def dropBraceFree(): Unit = {
        drop(Backtick)
        drop(Bracket)
        drop(Slashes)
        if (dollarTag.length > 2) drop(Dollar)
      }

      /** Opens a dollar-quoted string at `i` when a tag `$…$` starts there; copies the tag. */
      private def dollar(i: Int): Int = {
        var k = i + 1
        if (k < length && (sql.charAt(k).isLetter || sql.charAt(k) == '_' || sql.charAt(k) >= '\u0080'))
          while (k < length && identAt(k) && sql.charAt(k) != '$') k += 1
        if (!charAt(k, '$')) copy(i, 1)
        else {
          open(Dollar, i, k + 1)
          dollarTag = sql.substring(i, k + 1)
          copy(i, k + 1 - i)
        }
      }

      /** Opens the backtick, bracket or `q'…'` quoting that may start at `i`, unless one of its kind is open. */
      private def openAt(i: Int, c: Char): Unit = c match {
        // after an operator character a backtick belongs to a PostgreSQL operator
        case '`' if opened(Backtick) < 0 && !(i > 0 && "+-*/<>=~!@#%^&|?".indexOf(sql.charAt(i - 1)) >= 0) =>
          open(Backtick, i, i + 1)
        case '[' if opened(Bracket) < 0 && bracketOpens(i)                                                 =>
          open(Bracket, i, i + 1)
        case 'q' | 'Q' | 'n' | 'N' if opened(QQuote) < 0 && !identAt(i - 1)                                =>
          val q         = if (c == 'n' || c == 'N') i + 1 else i
          val delimiter = if (q + 2 < length) sql.charAt(q + 2) else ' '
          if (
            (charAt(q, 'q') || charAt(q, 'Q')) && charAt(q + 1, '\'') && !delimiter.isWhitespace && "{}'".indexOf(delimiter) < 0
          ) {
            qClose = delimiter match {
              case '[' => ']'
              case '(' => ')'
              case '<' => '>'
              case _   => delimiter
            }
            open(QQuote, i, q + 3)
          }
        case _                                                                                             =>
      }

      /** A bracket opens an identifier, not after an expression (`a[1]`, `f(x)[1]`) or the array constructor (`ARRAY [1]`). */
      private def bracketOpens(i: Int): Boolean =
        !identAt(i - 1) && !charAt(i - 1, ']') && !charAt(i - 1, ')') && {
          var j = i - 1
          while (j >= 0 && sql.charAt(j).isWhitespace) j -= 1
          !(j < i - 1 && j >= 4 && sql.regionMatches(true, j - 4, "ARRAY", 0, 5) && !identAt(j - 5))
        }

      /** Finds the dialect quoting that closes at `i`; when several do, the earliest opened, which holds the others. */
      private def findClosing(i: Int, c: Char): Unit = {
        closing = -1
        var kind = 0
        while (kind < Kinds) {
          if (opened(kind) >= 0 && i >= bodyFrom(kind) && (closing < 0 || opened(kind) < opened(closing))) {
            val end = closesAt(kind, i, c)
            if (end >= 0) {
              closing = kind
              closingEnd = end
            }
          }
          kind += 1
        }
      }

      /** Where the quoting of `kind` ends when it closes at `i`, else -1. */
      private def closesAt(kind: Int, i: Int, c: Char): Int = kind match {
        case Backtick => if (c == '`') i + 1 else -1
        case Bracket  => if (c == ']') i + 1 else -1
        case Slashes  => if (c == '\n' || c == '\r') i else -1
        case Dollar   =>
          if (c != '$') -1
          else if (dollarTag.length == 2) { if (charAt(i + 1, '$')) i + 2 else -1 }
          else if (state == Normal && sql.startsWith(dollarTag, i)) i + dollarTag.length
          else -1
        case _        => if (state == Normal && c == qClose && charAt(i + 1, '\'')) i + 2 else -1
      }

      /** Copies the quoting that closed as written, in place of what was read of it, and returns where to read next. */
      private def close(): Int = {
        val from = opened(closing)
        query.setLength(queryAt(closing))
        query.append(sql, from, closingEnd)
        names.dropRightInPlace(names.length - namesAt(closing))
        name.setLength(0)
        state = Normal
        var kind = 0
        while (kind < Kinds) {
          if (opened(kind) >= from) drop(kind)
          kind += 1
        }
        closing = -1
        closingEnd
      }
    }

    /** A node of the trie of placeholder names: the next node by character, and the name's group when a name ends here. */
    private final class TrieNode {
      var next: java.util.HashMap[Character, TrieNode] = _
      var group: Int                                   = -1
    }

    /** The placeholders of one SQL, by name: each name in the order it first appears, with its positions, newest first. The
      * names are kept in a trie, so building the map and finding a name cost the length of the name, whatever the names are: no
      * two names are compared and no hash code of a name is used.
      */
    private final class Placeholders(placeholders: collection.Seq[String]) extends AbstractMap[String, List[Int]] {
      private val root       = new TrieNode
      private val byFirstUse = new ArrayBuffer[String]
      private val positions  = new ArrayBuffer[List[Int]]

      private def find(key: String, add: Boolean): TrieNode = {
        var node = root
        var i    = 0
        while (node != null && i < key.length) {
          val c    = Character.valueOf(key.charAt(i))
          var next = if (node.next == null) null else node.next.get(c)
          if (next == null && add) {
            next = new TrieNode
            if (node.next == null) node.next = new java.util.HashMap[Character, TrieNode]
            node.next.put(c, next)
          }
          node = next
          i += 1
        }
        node
      }

      private var position = 0
      while (position < placeholders.length) {
        val node = find(placeholders(position), add = true)
        if (node.group < 0) {
          node.group = byFirstUse.length
          byFirstUse += placeholders(position)
          positions += Nil
        }
        position += 1
        positions(node.group) = position :: positions(node.group)
      }

      override def get(key: String): Option[List[Int]] = {
        val node = find(key, add = false)
        if (node == null || node.group < 0) None else Some(positions(node.group))
      }

      override def iterator: Iterator[(String, List[Int])] = byFirstUse.iterator.zip(positions.iterator)
      override def size: Int                               = byFirstUse.length
      override def knownSize: Int                          = byFirstUse.length

      override def removed(key: String): Map[String, List[Int]]                      = Map.from(iterator).removed(key)
      override def updated[V1 >: List[Int]](key: String, value: V1): Map[String, V1] = Map.from(iterator).updated(key, value)
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

  def apply(pool: HikariDataSource, blockingPool: ExecutorService, queryTimeout: Option[FiniteDuration] = None): JDBCClient =
    new JDBCClient(pool, blockingPool, queryTimeout)
}

class JDBCClient(pool: HikariDataSource, val blockingPool: ExecutorService, queryTimeout: Option[FiniteDuration] = None) {
  // Enforced in the primary constructor, not just the companion `apply` (#88): a public class with no guard here would let
  // `new JDBCClient(...)` bypass the check entirely and reproduce the silent OK-without-persistence defect.
  if (!pool.isAutoCommit) throw new IllegalArgumentException(JDBCClient.AutoCommitRequiredMessage)

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
    *
    * The cap is enforced by reading at most `cap` rows, then peeking one more `ResultSet.next()` call WITHOUT mapping or
    * detaching it: an overflow row is rejected without its (possibly large BLOB/CLOB) content ever being copied into memory —
    * doing that first would defeat the cap's own point.
    */
  def executeSelect[U](
      sql: String,
      params: Seq[(String, ParamVal)],
      maxRows: Option[Int],
  )(consumer: Try[List[Map[String, Any]]] => U): Future[U] = Future {
    val result = withPreparedStatement(sql, params.toMap) { (use, stmt) =>
      maxRows.foreach(applyDriverRowGuard(stmt, _))
      val rs     = use(stmt.executeQuery)
      val labels = validatedResultLabels(rs)
      val rows   = List.newBuilder[Map[String, Any]]
      maxRows match {
        case Some(cap) =>
          var read = 0
          while (read < cap && rs.next()) {
            rows += record(rs, labels)
            read += 1
          }
          if (rs.next()) throw maxRowsExceeded(cap)
        case None      =>
          while (rs.next()) rows += record(rs, labels)
      }
      rows.result()
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

  private def maxRowsExceeded(cap: Int): IllegalStateException = new MaxRowsExceededException(cap)

  /** Best-effort driver-side transfer guard; correctness always comes from counting while reading. Skipped at Int.MaxValue —
    * `cap + 1` would overflow, and a list cannot hold more rows anyway. `setLargeMaxRows` is deliberately NOT used: drivers
    * without it (pgjdbc) raise SQLFeatureNotSupportedException with SQLSTATE 0A000, which HikariCP treats as a connection error
    * state and poisons the pooled connection.
    */
  private def applyDriverRowGuard(stmt: Statement, cap: Int): Unit =
    if (cap < Int.MaxValue) stmt.setMaxRows(cap + 1)

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
