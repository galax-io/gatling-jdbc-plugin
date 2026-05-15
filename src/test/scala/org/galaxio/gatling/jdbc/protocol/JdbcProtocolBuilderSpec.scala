package org.galaxio.gatling.jdbc.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class JdbcProtocolBuilderSpec extends AnyFlatSpec with Matchers {

  "JdbcProtocolBuilderConnectionSettingsStep" should "default blockingPoolSize to maximumPoolSize" in {
    val builder = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:h2:mem:test",
      username = "sa",
      password = "",
      maximumPoolSize = 12,
    )

    val protocol = builder.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.blockingPoolSize shouldBe 12
  }

  it should "allow overriding blockingPoolSize explicitly" in {
    val builder = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:h2:mem:test",
      username = "sa",
      password = "",
      maximumPoolSize = 12,
      blockingPoolSize = Some(5),
      connectionTimeout = 30.seconds,
    )

    val protocol = builder.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.blockingPoolSize shouldBe 5
  }

  it should "default queryTimeout to None" in {
    val builder = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:h2:mem:test",
      username = "sa",
      password = "",
    )

    val protocol = builder.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.queryTimeout shouldBe None
  }

  it should "allow setting queryTimeout" in {
    val builder = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:h2:mem:test",
      username = "sa",
      password = "",
    ).queryTimeout(10.seconds)

    val protocol = builder.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.queryTimeout shouldBe Some(10.seconds)
  }

  "JdbcProtocolBuilderBase" should "build from HikariConfig" in {
    val cfg = new com.zaxxer.hikari.HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:test")
    cfg.setMaximumPoolSize(8)

    val protocol = JdbcProtocolBuilderBase.hikariConfig(cfg).build.asInstanceOf[JdbcProtocol]

    protocol.blockingPoolSize shouldBe 8
    protocol.queryTimeout shouldBe None
  }

  "Builder step chain" should "support fluent API" in {
    val step = JdbcProtocolBuilderBase
      .url("jdbc:h2:mem:test")
      .username("sa")
      .password("")
      .maximumPoolSize(16)
      .minimumIdleConnections(4)
      .blockingPoolSize(8)
      .connectionTimeout(45.seconds)
      .queryTimeout(5.seconds)

    val protocol = step.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.blockingPoolSize shouldBe 8
    protocol.queryTimeout shouldBe Some(5.seconds)
    protocol.hikariConfig.getMaximumPoolSize shouldBe 16
    protocol.hikariConfig.getMinimumIdle shouldBe 4
    protocol.hikariConfig.getConnectionTimeout shouldBe 45000L
  }
}
