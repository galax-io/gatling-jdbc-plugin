package org.galaxio.gatling.jdbc.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression for issue #91 (spec 005 US2): the connection-settings builder step never renders the password — not in its own
  * `toString`, not through URL-embedded credentials, and not inside an exception message that captures the config.
  * `copy`/`apply` stay intact.
  */
class BuilderRedactionSpec extends AnyFlatSpec with Matchers {

  private val marker    = "s3cr3t-MARKER-91"
  private val urlMarker = "pw-URLMARKER-91"

  private def step(url: String = "jdbc:h2:mem:redaction", password: String = marker) =
    JdbcProtocolBuilderConnectionSettingsStep(url = url, username = "usr", password = password)

  "the connection-settings step toString" should "never contain the password value" in {
    step().toString should not include marker
  }

  it should "render URL-embedded credentials redacted" in {
    val s = step(url = s"jdbc:postgresql://usr:$urlMarker@host:5432/db")
    s.toString should not include urlMarker
    s.toString should include(s"usr:${Redaction.Mask}@")
  }

  "copy and apply" should "still behave as a normal case class" in {
    val s        = step()
    val recopied = s.copy(username = "other")
    recopied.username shouldBe "other"
    recopied.password shouldBe marker // value still carried, just never rendered
    s.username shouldBe "usr"
  }

  "a malformed URL" should "redact rather than throw" in {
    val s = step(url = "not a url with creds @ inside")
    noException should be thrownBy s.toString
  }

  // FR-005: "including inside exception messages" — a real failure that carries the config must not leak the password set
  // through the builder (Hikari masks setPassword; the plugin must not reintroduce it). Credentials embedded directly in the
  // JDBC URL are a documented anti-pattern (Hikari echoes the raw URL in its own exceptions, outside plugin control).
  "a pool-construction failure carrying the config" should "not leak the builder password in any exception message" in {
    val badStep  = JdbcProtocolBuilderConnectionSettingsStep(
      url = "jdbc:postgresql://127.0.0.1:1/nope",
      username = "usr",
      password = marker,
      connectionTimeout = scala.concurrent.duration.DurationInt(1).second,
    )
    val protocol = badStep.protocolBuilder.build.asInstanceOf[JdbcProtocol]

    val ex = intercept[Throwable] {
      new com.zaxxer.hikari.HikariDataSource(protocol.hikariConfig).getConnection()
    }

    // walk the whole cause chain
    var t: Throwable = ex
    while (t != null) {
      Option(t.getMessage).foreach(_ should not include marker)
      t = t.getCause
    }
  }
}
