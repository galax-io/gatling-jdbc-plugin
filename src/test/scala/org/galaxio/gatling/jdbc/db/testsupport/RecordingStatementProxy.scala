package org.galaxio.gatling.jdbc.db.testsupport

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{CallableStatement, PreparedStatement}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

/** JDK-proxy instrumentation for the parameter-binding concurrency contract (issues #120/#121).
  *
  * Wraps a real statement and records, for every indexed parameter call (`setInt`, `setString`, ..., `registerOutParameter`):
  *   - the maximum number of such calls in progress at the same instant (must stay 1 — no overlap),
  *   - how many times each JDBC index was bound/registered (must be exactly once per index),
  *   - the value bound at each index (must equal the declared value).
  *
  * Every call is delegated to the real statement, so the instrumented statement still executes against the real database.
  * `holdMillis` keeps each tracked call inside the handler for a small window so that overlapping calls, if any, are reliably
  * observed as `maxConcurrentParamCalls > 1`.
  */
final class RecordingStatementHandler(delegate: AnyRef, holdMillis: Long) extends InvocationHandler {

  /** Marker stored instead of `null` bound values (ConcurrentHashMap cannot hold nulls). */
  val NullValue: AnyRef = RecordingStatementProxy.NullValue

  private val inFlight    = new AtomicInteger(0)
  private val maxInFlight = new AtomicInteger(0)

  private val setterCalls   = new ConcurrentHashMap[Integer, AtomicInteger]()
  private val setterValues  = new ConcurrentHashMap[Integer, AnyRef]()
  private val registerCalls = new ConcurrentHashMap[Integer, AtomicInteger]()

  def maxConcurrentParamCalls: Int = maxInFlight.get()

  def setterCountsByIndex: Map[Int, Int] =
    setterCalls.asScala.map { case (k, v) => (k.intValue, v.get) }.toMap

  def registerCountsByIndex: Map[Int, Int] =
    registerCalls.asScala.map { case (k, v) => (k.intValue, v.get) }.toMap

  def boundValueAt(index: Int): AnyRef = setterValues.get(Integer.valueOf(index))

  private def isTrackedParamCall(method: Method): Boolean = {
    val paramTypes = method.getParameterTypes
    val indexed    = paramTypes.nonEmpty && paramTypes(0) == java.lang.Integer.TYPE
    (method.getName.startsWith("set") && paramTypes.length >= 2 && indexed) ||
    (method.getName == "registerOutParameter" && indexed)
  }

  override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
    if (isTrackedParamCall(method)) {
      val current = inFlight.incrementAndGet()
      maxInFlight.updateAndGet(m => math.max(m, current))
      try {
        if (holdMillis > 0) Thread.sleep(holdMillis)
        val index = args(0).asInstanceOf[Integer]
        if (method.getName == "registerOutParameter")
          registerCalls.computeIfAbsent(index, _ => new AtomicInteger(0)).incrementAndGet()
        else {
          setterCalls.computeIfAbsent(index, _ => new AtomicInteger(0)).incrementAndGet()
          setterValues.put(index, if (args.length > 1 && args(1) != null) args(1) else NullValue)
        }
        delegateCall(method, args)
      } finally inFlight.decrementAndGet()
    } else delegateCall(method, args)

  private def delegateCall(method: Method, args: Array[AnyRef]): AnyRef =
    try method.invoke(delegate, args: _*)
    catch { case e: InvocationTargetException => throw e.getCause }
}

object RecordingStatementProxy {

  case object NullValue

  def prepared(delegate: PreparedStatement, holdMillis: Long = 1): (PreparedStatement, RecordingStatementHandler) = {
    val handler = new RecordingStatementHandler(delegate, holdMillis)
    val proxy   = Proxy
      .newProxyInstance(classOf[PreparedStatement].getClassLoader, Array(classOf[PreparedStatement]), handler)
      .asInstanceOf[PreparedStatement]
    (proxy, handler)
  }

  def callable(delegate: CallableStatement, holdMillis: Long = 1): (CallableStatement, RecordingStatementHandler) = {
    val handler = new RecordingStatementHandler(delegate, holdMillis)
    val proxy   = Proxy
      .newProxyInstance(classOf[CallableStatement].getClassLoader, Array(classOf[CallableStatement]), handler)
      .asInstanceOf[CallableStatement]
    (proxy, handler)
  }
}
