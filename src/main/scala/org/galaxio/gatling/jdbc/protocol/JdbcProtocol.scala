package org.galaxio.gatling.jdbc.protocol

import com.zaxxer.hikari._
import io.gatling.core.CoreComponents
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.protocol._
import org.galaxio.gatling.jdbc.db._

import java.util.concurrent.Executors

object JdbcProtocol {
  val jdbcProtocolKey: ProtocolKey[JdbcProtocol, JdbcComponents] = new ProtocolKey[JdbcProtocol, JdbcComponents] {
    override def protocolClass: Class[Protocol] = classOf[JdbcProtocol].asInstanceOf[Class[Protocol]]

    override def defaultProtocolValue(configuration: GatlingConfiguration): JdbcProtocol =
      throw new IllegalStateException("Can't provide a default value for JdbcProtocol")

    override def newComponents(coreComponents: CoreComponents): JdbcProtocol => JdbcComponents =
      protocol => {
        val blockingPool   = Executors.newCachedThreadPool()
        val connectionPool = new HikariDataSource(protocol.hikariConfig)
        JdbcComponents(JDBCClient(connectionPool, blockingPool))
      }
  }

}

case class JdbcProtocol(hikariConfig: HikariConfig) extends Protocol {
  type Components = JdbcComponents
}
