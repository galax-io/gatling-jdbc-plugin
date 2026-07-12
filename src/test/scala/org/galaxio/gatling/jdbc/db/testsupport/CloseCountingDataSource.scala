package org.galaxio.gatling.jdbc.db.testsupport

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{CallableStatement, Connection, PreparedStatement, ResultSet, Statement}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

/** One resource acquired through [[CloseCountingDataSource]] and the number of times it was closed. */
final class TrackedResource(val kind: String) {
  val closeCount = new AtomicInteger(0)
}

/** Test-only HikariDataSource that counts `close()` per acquired resource (issue #100, contract G4).
  *
  * Every connection handed out — and every statement/result set created through it — is wrapped in a JDK proxy that delegates
  * all calls to the real pooled resource while recording each `close()`. This proves the exactly-once release invariant per
  * resource, which pool-level metrics alone cannot (a zero active count says connections came back, not that each statement and
  * result set was released exactly once).
  *
  * Setting `failNextStatementClose` makes the next statement `close()` throw AFTER delegating to the real close (the pool stays
  * clean), which lets tests assert that a non-fatal release failure is suppressed under the original operation failure instead
  * of masking it.
  */
final class CloseCountingDataSource(cfg: HikariConfig) extends HikariDataSource(cfg) {

  private val tracked = new ConcurrentLinkedQueue[TrackedResource]()

  /** Check-then-reset is not atomic: arm this only while a single operation is in flight. */
  @volatile var failNextStatementClose: Boolean = false

  def trackedResources: List[TrackedResource] = tracked.asScala.toList

  def resetTracking(): Unit = {
    tracked.clear()
    failNextStatementClose = false
  }

  override def getConnection: Connection = wrap(super.getConnection, classOf[Connection], "connection")

  private def wrap[T](delegate: AnyRef, iface: Class[T], kind: String): T = {
    val record = new TrackedResource(kind)
    tracked.add(record)

    val handler = new InvocationHandler {
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
        case "close"                                    =>
          record.closeCount.incrementAndGet()
          delegateCall(method, args)
          if (kind == "statement" && failNextStatementClose) {
            failNextStatementClose = false
            throw new RuntimeException("injected close failure")
          }
          null
        case "createStatement" if kind == "connection"  =>
          wrap(delegateCall(method, args), classOf[Statement], "statement")
        case "prepareStatement" if kind == "connection" =>
          wrap(delegateCall(method, args), classOf[PreparedStatement], "statement")
        case "prepareCall" if kind == "connection"      =>
          wrap(delegateCall(method, args), classOf[CallableStatement], "statement")
        case "executeQuery" if kind == "statement"      =>
          wrap(delegateCall(method, args), classOf[ResultSet], "resultset")
        case _                                          =>
          delegateCall(method, args)
      }

      private def delegateCall(method: Method, args: Array[AnyRef]): AnyRef =
        try method.invoke(delegate, args: _*)
        catch { case e: InvocationTargetException => throw e.getCause }
    }

    Proxy.newProxyInstance(iface.getClassLoader, Array(iface), handler).asInstanceOf[T]
  }
}
