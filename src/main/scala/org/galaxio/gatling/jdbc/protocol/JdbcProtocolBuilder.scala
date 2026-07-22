package org.galaxio.gatling.jdbc.protocol

import com.zaxxer.hikari.HikariConfig
import io.gatling.core.protocol.Protocol

import scala.concurrent.duration._

case object JdbcProtocolBuilderBase {

  def url(url: String): JdbcProtocolBuilderUsernameStep    = JdbcProtocolBuilderUsernameStep(url)
  def hikariConfig(cfg: HikariConfig): JdbcProtocolBuilder = JdbcProtocolBuilder(cfg, cfg.getMaximumPoolSize)

}

case class JdbcProtocolBuilderUsernameStep(url: String) {

  def username(newValue: String): JdbcProtocolBuilderPasswordStep = JdbcProtocolBuilderPasswordStep(url, newValue)

}

case class JdbcProtocolBuilderPasswordStep(url: String, username: String) {

  def password(newValue: String): JdbcProtocolBuilderConnectionSettingsStep =
    JdbcProtocolBuilderConnectionSettingsStep(url, username, newValue)

}

final case class JdbcProtocolBuilderConnectionSettingsStep(
    url: String,
    username: String,
    password: String,
    maximumPoolSize: Int = 10,
    minimumIdleConnections: Int = 10,
    blockingPoolSize: Option[Int] = None,
    connectionTimeout: FiniteDuration = 1.minute,
    queryTimeout: Option[FiniteDuration] = None,
) {
  def protocolBuilder: JdbcProtocolBuilder = {
    val hikariConfig = new HikariConfig()

    hikariConfig.setUsername(username)
    hikariConfig.setPassword(password)
    hikariConfig.setJdbcUrl(url)
    hikariConfig.setMaximumPoolSize(maximumPoolSize)
    hikariConfig.setMinimumIdle(minimumIdleConnections)
    hikariConfig.setConnectionTimeout(connectionTimeout.toMillis)

    JdbcProtocolBuilder(hikariConfig, blockingPoolSize.getOrElse(maximumPoolSize), queryTimeout)
  }

  def maximumPoolSize(newValue: Int): JdbcProtocolBuilderConnectionSettingsStep              =
    this.copy(maximumPoolSize = newValue)
  def minimumIdleConnections(newValue: Int): JdbcProtocolBuilderConnectionSettingsStep       =
    this.copy(minimumIdleConnections = newValue)
  def blockingPoolSize(newValue: Int): JdbcProtocolBuilderConnectionSettingsStep             =
    this.copy(blockingPoolSize = Some(newValue))
  def connectionTimeout(newValue: FiniteDuration): JdbcProtocolBuilderConnectionSettingsStep =
    this.copy(connectionTimeout = newValue)

  /** Sets the query timeout for all JDBC statements.
    * @param newValue
    *   timeout duration. Sub-second values are rounded up to 1 second. Omit this setting for no timeout. Passing
    *   `Duration.Zero` is equivalent to no timeout (JDBC semantics).
    */
  def queryTimeout(newValue: FiniteDuration): JdbcProtocolBuilderConnectionSettingsStep = {
    require(newValue >= Duration.Zero, "queryTimeout must be non-negative")
    this.copy(queryTimeout = Some(newValue))
  }

  /** #91: the synthesized case-class `toString` would print the password and any URL-embedded credentials wherever this step is
    * logged or folded into an exception. Render the password masked and the URL credential-redacted; every other field is
    * reproduced as-is so diagnostics stay useful. `apply`/`copy`/`unapply` are untouched.
    */
  override def toString: String =
    s"JdbcProtocolBuilderConnectionSettingsStep(url=${Redaction.redactUrl(url)}, username=$username, " +
      s"password=${Redaction.Mask}, maximumPoolSize=$maximumPoolSize, minimumIdleConnections=$minimumIdleConnections, " +
      s"blockingPoolSize=$blockingPoolSize, connectionTimeout=$connectionTimeout, queryTimeout=$queryTimeout)"
}

final case class JdbcProtocolBuilder(
    hikariConfig: HikariConfig,
    private[protocol] val blockingPoolSize: Int,
    queryTimeout: Option[FiniteDuration] = None,
) {

  def build: Protocol = JdbcProtocol(hikariConfig, blockingPoolSize, queryTimeout)

}
