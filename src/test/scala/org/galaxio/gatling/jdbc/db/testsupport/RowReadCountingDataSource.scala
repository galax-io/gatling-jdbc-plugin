package org.galaxio.gatling.jdbc.db.testsupport

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{CallableStatement, Connection, PreparedStatement, ResultSet, Statement}
import java.util.concurrent.atomic.AtomicInteger

/** Test-only HikariDataSource that counts `ResultSet.getObject` calls (same JDK-proxy pattern as [[CloseCountingDataSource]]
  * and [[FailingConnectionDataSource]]), used to prove the `maxRows` cap never maps/detaches an overflow row's column values
  * (#86 follow-up): H2 can evaluate row expressions eagerly at `executeQuery()` time rather than lazily per `next()`, so a
  * side-effecting SQL function can't reliably prove non-consumption — counting the actual JDBC calls can.
  */
final class RowReadCountingDataSource(cfg: HikariConfig) extends HikariDataSource(cfg) {

  private val counter = new AtomicInteger(0)

  def getObjectCount: Int = counter.get()
  def resetCount(): Unit  = counter.set(0)

  override def getConnection: Connection =
    wrap(super.getConnection, classOf[Connection], isConnection = true).asInstanceOf[Connection]

  private def wrap(delegate: AnyRef, iface: Class[_], isConnection: Boolean): AnyRef = {
    val handler = new InvocationHandler {
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
        case "getObject" if !isConnection && iface == classOf[ResultSet] =>
          counter.incrementAndGet()
          delegateCall(method, args)

        case "createStatement" if isConnection  =>
          wrap(delegateCall(method, args), classOf[Statement], isConnection = false)
        case "prepareStatement" if isConnection =>
          wrap(delegateCall(method, args), classOf[PreparedStatement], isConnection = false)
        case "prepareCall" if isConnection      =>
          wrap(delegateCall(method, args), classOf[CallableStatement], isConnection = false)
        case "executeQuery" if !isConnection    =>
          wrap(delegateCall(method, args), classOf[ResultSet], isConnection = false)

        case _ => delegateCall(method, args)
      }

      private def delegateCall(method: Method, args: Array[AnyRef]): AnyRef =
        try method.invoke(delegate, args: _*)
        catch { case e: InvocationTargetException => throw e.getCause }
    }

    Proxy.newProxyInstance(iface.getClassLoader, Array(iface), handler)
  }
}
