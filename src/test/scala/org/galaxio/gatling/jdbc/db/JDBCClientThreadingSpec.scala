package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{InvocationHandler, Proxy}
import java.sql.{Connection, Statement}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, ThreadFactory, TimeUnit}

class JDBCClientThreadingSpec extends AnyFlatSpec with Matchers {

  private val requestCount = 48

  "JDBCClient with bounded thread pool" should "cap worker growth under blocked JDBC calls" in {
    val releaseConnections            = new CountDownLatch(1)
    val blockingPoolSize              = 8
    val startedConnections            = new CountDownLatch(blockingPoolSize)
    val threadFactory                 = new CountingThreadFactory("jdbc-blocking")
    val blockingPool: ExecutorService = Executors.newFixedThreadPool(blockingPoolSize, threadFactory)
    val dataSource                    = new BlockingDataSource(startedConnections, releaseConnections)
    val client                        = JDBCClient(dataSource, blockingPool)
    val completions                   = new CountDownLatch(requestCount)

    try {
      (1 to requestCount).foreach { _ =>
        client.executeRaw("SELECT 1")(
          _ => completions.countDown(),
          _ => completions.countDown(),
        )
      }

      startedConnections.await(5, TimeUnit.SECONDS) shouldBe true

      val workerThreadsCreated = threadFactory.createdCount.get()
      workerThreadsCreated should be <= blockingPoolSize
    } finally {
      releaseConnections.countDown()
      completions.await(5, TimeUnit.SECONDS)
      client.close()
    }
  }

  private final class CountingThreadFactory(prefix: String) extends ThreadFactory {
    private val delegate            = Executors.defaultThreadFactory()
    val createdCount: AtomicInteger = new AtomicInteger(0)

    override def newThread(runnable: Runnable): Thread = {
      val threadNumber = createdCount.incrementAndGet()
      val thread       = delegate.newThread(runnable)
      thread.setName(s"$prefix-$threadNumber")
      thread
    }
  }

  private final class BlockingDataSource(
      startedConnections: CountDownLatch,
      releaseConnections: CountDownLatch,
  ) extends HikariDataSource {

    private val statement: Statement = proxy[Statement] {
      case "execute" => java.lang.Boolean.TRUE
      case "close"   => null
    }

    private val connection: Connection = proxy[Connection] {
      case "createStatement" => statement
      case "close"           => null
    }

    override def getConnection: Connection = {
      startedConnections.countDown()
      releaseConnections.await(5, TimeUnit.SECONDS)
      connection
    }

    override def close(): Unit = ()
  }

  private def proxy[T](handler: PartialFunction[String, AnyRef])(implicit clazz: reflect.ClassTag[T]): T = {
    val invocationHandler = new InvocationHandler {
      override def invoke(proxy: Any, method: java.lang.reflect.Method, args: Array[AnyRef]): AnyRef = {
        if (handler.isDefinedAt(method.getName)) {
          handler(method.getName)
        } else {
          method.getReturnType match {
            case java.lang.Boolean.TYPE => java.lang.Boolean.FALSE
            case java.lang.Integer.TYPE => Int.box(0)
            case java.lang.Long.TYPE    => Long.box(0L)
            case java.lang.Void.TYPE    => null
            case _                      => null
          }
        }
      }
    }

    Proxy
      .newProxyInstance(clazz.runtimeClass.getClassLoader, Array(clazz.runtimeClass), invocationHandler)
      .asInstanceOf[T]
  }
}
