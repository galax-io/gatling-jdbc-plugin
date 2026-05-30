package org.galaxio.gatling.jdbc.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{Duration, DurationInt}

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

  it should "flow queryTimeout through to JdbcProtocol" in {
    val builder = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:h2:mem:test",
      username = "sa",
      password = "",
    ).queryTimeout(30.seconds)

    val protocol = builder.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.queryTimeout shouldBe Some(30.seconds)
  }

  it should "preserve None when queryTimeout is not set" in {
    val builder = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:h2:mem:test",
      username = "sa",
      password = "",
    )

    val protocol = builder.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.queryTimeout shouldBe None
  }

  it should "allow zero queryTimeout (JDBC semantics: no limit)" in {
    val builder = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:h2:mem:test",
      username = "sa",
      password = "",
    ).queryTimeout(Duration.Zero)

    val protocol = builder.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    protocol.queryTimeout shouldBe Some(Duration.Zero)
  }

  it should "reject negative queryTimeout" in {
    an[IllegalArgumentException] should be thrownBy {
      JdbcProtocolBuilderConnectionSettingsStep(
        url = "jdbc:h2:mem:test",
        username = "sa",
        password = "",
      ).queryTimeout(-1.seconds)
    }
  }
}
