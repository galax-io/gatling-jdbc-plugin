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
) {
  def protocolBuilder: JdbcProtocolBuilder = {
    val hikariConfig = new HikariConfig()

    hikariConfig.setUsername(username)
    hikariConfig.setPassword(password)
    hikariConfig.setJdbcUrl(url)
    hikariConfig.setMaximumPoolSize(maximumPoolSize)
    hikariConfig.setMinimumIdle(minimumIdleConnections)
    hikariConfig.setConnectionTimeout(connectionTimeout.toMillis)

    JdbcProtocolBuilder(hikariConfig, blockingPoolSize.getOrElse(maximumPoolSize))
  }

  def maximumPoolSize(newValue: Int): JdbcProtocolBuilderConnectionSettingsStep              =
    this.copy(maximumPoolSize = newValue)
  def minimumIdleConnections(newValue: Int): JdbcProtocolBuilderConnectionSettingsStep       =
    this.copy(minimumIdleConnections = newValue)
  def blockingPoolSize(newValue: Int): JdbcProtocolBuilderConnectionSettingsStep             =
    this.copy(blockingPoolSize = Some(newValue))
  def connectionTimeout(newValue: FiniteDuration): JdbcProtocolBuilderConnectionSettingsStep =
    this.copy(connectionTimeout = newValue)
}

final case class JdbcProtocolBuilder(hikariConfig: HikariConfig, blockingPoolSize: Int) {

  def build: Protocol = JdbcProtocol(hikariConfig, blockingPoolSize)

}
