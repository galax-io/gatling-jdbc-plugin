package org.galaxio.gatling.jdbc.protocol

import com.zaxxer.hikari._
import io.gatling.core.CoreComponents
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.protocol._
import org.galaxio.gatling.jdbc.db._

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.FiniteDuration

object JdbcProtocol {
  private final class JdbcThreadFactory(prefix: String) extends ThreadFactory {
    private val delegate = Executors.defaultThreadFactory()
    private val counter  = new AtomicInteger(0)

    override def newThread(runnable: Runnable): Thread = {
      val thread = delegate.newThread(runnable)
      thread.setName(s"$prefix-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }

  val jdbcProtocolKey: ProtocolKey[JdbcProtocol, JdbcComponents] = new ProtocolKey[JdbcProtocol, JdbcComponents] {
    override def protocolClass: Class[Protocol] = classOf[JdbcProtocol].asInstanceOf[Class[Protocol]]

    override def defaultProtocolValue(configuration: GatlingConfiguration): JdbcProtocol =
      throw new IllegalStateException("Can't provide a default value for JdbcProtocol")

    override def newComponents(coreComponents: CoreComponents): JdbcProtocol => JdbcComponents =
      protocol => {
        val blockingPool   = Executors.newFixedThreadPool(protocol.blockingPoolSize, new JdbcThreadFactory("jdbc-blocking"))
        val connectionPool = new HikariDataSource(protocol.hikariConfig)
        JdbcComponents(
          JDBCClient(connectionPool, blockingPool, protocol.queryTimeout.map(_.toSeconds.toInt)),
          connectionPool,
        )
      }
  }

}

case class JdbcProtocol(
    hikariConfig: HikariConfig,
    blockingPoolSize: Int,
    queryTimeout: Option[FiniteDuration] = None,
) extends Protocol {
  type Components = JdbcComponents
}
