package org.galaxio.gatling.jdbc.db.testsupport

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{BatchUpdateException, CallableStatement, Connection, PreparedStatement, SQLException, Statement}

/** Test-only HikariDataSource that injects deterministic failures into the batch cleanup path (issue #84).
  *
  * Drivers cannot be forced to fail `rollback` or `close` on demand, so every connection handed out — and every statement
  * created through it — is wrapped in a JDK proxy over the real pooled resource (same pattern as [[CloseCountingDataSource]]):
  *
  *   - `failExecuteBatch` — `executeBatch` throws a [[BatchUpdateException]] instead of delegating.
  *   - `failRollback` — `rollback` throws instead of delegating, simulating a dead connection: the transaction stays open, so
  *     partial writes survive until the connection itself is terminated.
  *   - `failStatementClose` / `failConnectionClose` — `close` throws AFTER delegating to the real close, so the pool stays
  *     clean while the caller still observes a cleanup failure.
  *
  * Flags are one-operation switches: arm them only while a single operation is in flight.
  */
final class FailingConnectionDataSource(cfg: HikariConfig) extends HikariDataSource(cfg) {

  @volatile var failExecuteBatch: Boolean    = false
  @volatile var failRollback: Boolean        = false
  @volatile var failStatementClose: Boolean  = false
  @volatile var failConnectionClose: Boolean = false

  def reset(): Unit = {
    failExecuteBatch = false
    failRollback = false
    failStatementClose = false
    failConnectionClose = false
  }

  override def getConnection: Connection =
    wrap(super.getConnection, classOf[Connection], isConnection = true).asInstanceOf[Connection]

  private def wrap(delegate: AnyRef, iface: Class[_], isConnection: Boolean): AnyRef = {
    val handler = new InvocationHandler {
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
        case "executeBatch" if !isConnection && failExecuteBatch =>
          throw new BatchUpdateException("injected executeBatch failure", Array.empty[Int])

        case "rollback" if isConnection && failRollback =>
          throw new SQLException("injected rollback failure")

        case "close" =>
          delegateCall(method, args)
          if (isConnection && failConnectionClose) {
            failConnectionClose = false
            throw new SQLException("injected connection close failure")
          }
          if (!isConnection && failStatementClose) {
            failStatementClose = false
            throw new SQLException("injected statement close failure")
          }
          null

        case "createStatement" if isConnection  =>
          wrap(delegateCall(method, args), classOf[Statement], isConnection = false)
        case "prepareStatement" if isConnection =>
          wrap(delegateCall(method, args), classOf[PreparedStatement], isConnection = false)
        case "prepareCall" if isConnection      =>
          wrap(delegateCall(method, args), classOf[CallableStatement], isConnection = false)

        case _ => delegateCall(method, args)
      }

      private def delegateCall(method: Method, args: Array[AnyRef]): AnyRef =
        try method.invoke(delegate, args: _*)
        catch { case e: InvocationTargetException => throw e.getCause }
    }

    Proxy.newProxyInstance(iface.getClassLoader, Array(iface), handler)
  }
}
