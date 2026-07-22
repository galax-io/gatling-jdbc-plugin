package org.galaxio.gatling.jdbc.protocol

import com.zaxxer.hikari._
import io.gatling.core.CoreComponents
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.protocol._
import org.galaxio.gatling.jdbc.db._
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.FiniteDuration

object JdbcProtocol {

  private val log = LoggerFactory.getLogger("org.galaxio.gatling.jdbc.protocol.JdbcProtocol")

  /** #92: HikariCP masks its own `password`, but custom data-source properties with secret-like names (`sslpassword`, `token`,
    * …) and credentials embedded in the JDBC URL appear verbatim in Hikari's DEBUG config dump. The plugin cannot both keep
    * those values working (the driver needs them) and erase them from Hikari's internal logging, so it warns once at build time
    * — naming the property, never echoing the value — so an operator avoids DEBUG with real secrets in shared runs.
    */
  private[protocol] def warnOnSecretProperties(cfg: HikariConfig): Unit = {
    import scala.jdk.CollectionConverters._
    val secretNames = cfg.getDataSourceProperties.stringPropertyNames.asScala.filter(Redaction.isSecretProperty).toSeq.sorted
    if (secretNames.nonEmpty)
      log.warn(
        "Secret-like datasource propert{} {} will appear unmasked in HikariCP's own DEBUG config dump. " +
          "Avoid DEBUG logging with real secrets in shared runs, or pass credentials via setPassword (masked).",
        if (secretNames.sizeIs == 1) "y" else "ies",
        secretNames.mkString(", "),
      )
    val url         = cfg.getJdbcUrl
    if (url != null && Redaction.redactUrl(url) != url)
      log.warn(
        "The JDBC URL embeds credentials, which appear unmasked in HikariCP's own DEBUG config dump. " +
          "Prefer setUsername/setPassword (the password is masked) over credentials in the URL.",
      )
  }
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

  /** #88: must run before the executor or the connection pool exists — nothing to leak on the rejection path. */
  private[protocol] def validateHikariConfig(cfg: HikariConfig): Unit =
    if (!cfg.isAutoCommit) throw new IllegalArgumentException(JDBCClient.AutoCommitRequiredMessage)

  val jdbcProtocolKey: ProtocolKey[JdbcProtocol, JdbcComponents] = new ProtocolKey[JdbcProtocol, JdbcComponents] {
    override def protocolClass: Class[Protocol] = classOf[JdbcProtocol].asInstanceOf[Class[Protocol]]

    override def defaultProtocolValue(configuration: GatlingConfiguration): JdbcProtocol =
      throw new IllegalStateException("Can't provide a default value for JdbcProtocol")

    override def newComponents(coreComponents: CoreComponents): JdbcProtocol => JdbcComponents =
      protocol => {
        validateHikariConfig(protocol.hikariConfig)
        warnOnSecretProperties(protocol.hikariConfig)
        val blockingPool   = Executors.newFixedThreadPool(protocol.blockingPoolSize, new JdbcThreadFactory("jdbc-blocking"))
        val connectionPool = new HikariDataSource(protocol.hikariConfig)
        val client         = JDBCClient(connectionPool, blockingPool, protocol.queryTimeout)
        coreComponents.actorSystem.registerOnTermination { client.close() }
        JdbcComponents(client)
      }
  }

}

case class JdbcProtocol(
    hikariConfig: HikariConfig,
    private[protocol] val blockingPoolSize: Int,
    queryTimeout: Option[FiniteDuration] = None,
) extends Protocol {
  type Components = JdbcComponents
}
