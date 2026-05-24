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
}
