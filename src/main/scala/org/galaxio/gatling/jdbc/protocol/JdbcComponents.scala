package org.galaxio.gatling.jdbc.protocol

import com.zaxxer.hikari.HikariDataSource
import io.gatling.core.protocol.ProtocolComponents
import io.gatling.core.session.Session
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.slf4j.LoggerFactory

case class JdbcComponents(client: JDBCClient, dataSource: HikariDataSource) extends ProtocolComponents {
  private val logger = LoggerFactory.getLogger(classOf[JdbcComponents])

  override def onStart: Session => Session = Session.Identity

  override def onExit: Session => Unit = _ => {
    logPoolMetrics()
    client.close()
  }

  def logPoolMetrics(): Unit = {
    val pool = dataSource.getHikariPoolMXBean
    if (pool != null) {
      logger.info(
        s"HikariCP pool metrics — active: ${pool.getActiveConnections}, idle: ${pool.getIdleConnections}, " +
          s"waiting: ${pool.getThreadsAwaitingConnection}, total: ${pool.getTotalConnections}",
      )
    }
  }
}
