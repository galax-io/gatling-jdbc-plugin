package org.galaxio.gatling.jdbc.protocol

import ch.qos.logback.classic.Level
import com.zaxxer.hikari.HikariConfig
import org.galaxio.gatling.jdbc.db.testsupport.LogCapture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression for issue #92 (spec 005 US2), grounded in the observed behavior of HikariCP 7.1.0 (see the probe below).
  *
  * Probe findings (pinned here so an upgrade that regresses them fails):
  *   - the pool `password` (and a datasource property literally named `password`) is masked by Hikari itself → never leaks.
  *   - `sslpassword` / `token` are NOT masked: Hikari prints them in its own `dataSourceProperties...{…}` DEBUG dump.
  *
  * The plugin cannot both keep those values working (the driver needs them) and erase them from Hikari's internal logging, so
  * the fix is a build-time WARN naming each secret-like custom property (and URL-embedded credentials) — the issue's explicit
  * "fail/warn on unsafe custom properties" path. The plugin's own log output never contains these values.
  */
class HikariSecretPropsSpec extends AnyFlatSpec with Matchers {

  private val pluginLogger = "org.galaxio.gatling.jdbc.protocol"

  private def cfgWithSecrets(): HikariConfig = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:hikari_secret_regression;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("MARKER-92-password")
    cfg.addDataSourceProperty("sslpassword", "MARKER-92-sslpassword")
    cfg.addDataSourceProperty("token", "MARKER-92-token")
    cfg.addDataSourceProperty("maxPoolQueries", "500") // non-secret: must not be warned about
    cfg
  }

  // ─── pinned Hikari behavior ──────────────────────────────────────────────────

  "the pool password" should "be masked by Hikari and never leak at DEBUG (pinned)" in {
    val lines = LogCapture.capture(Seq("com.zaxxer.hikari"), Level.DEBUG) {
      val ds = new com.zaxxer.hikari.HikariDataSource(cfgWithSecrets())
      try ds.getConnection().close()
      finally ds.close()
    }
    lines.exists(_.contains("MARKER-92-password")) shouldBe false
  }

  // ─── plugin build-time warning (the deliverable) ─────────────────────────────

  "secret-like custom properties" should "trigger a build-time WARN naming each one" in {
    val warnings = LogCapture.capture(Seq(pluginLogger), Level.WARN) {
      JdbcProtocol.warnOnSecretProperties(cfgWithSecrets())
    }
    val all      = warnings.mkString("\n")
    all should include("sslpassword")
    all should include("token")
  }

  "non-secret custom properties" should "not be warned about" in {
    val warnings = LogCapture.capture(Seq(pluginLogger), Level.WARN) {
      JdbcProtocol.warnOnSecretProperties(cfgWithSecrets())
    }
    warnings.mkString("\n") should not include "maxPoolQueries"
  }

  "URL-embedded credentials" should "trigger a build-time WARN across driver URL forms" in {
    def warnFor(url: String): String = {
      val cfg = new HikariConfig()
      cfg.setJdbcUrl(url)
      LogCapture.capture(Seq(pluginLogger), Level.WARN)(JdbcProtocol.warnOnSecretProperties(cfg)).mkString("\n")
    }

    val authority = warnFor("jdbc:postgresql://usr:MARKER-92-urlpw@host:5432/db")
    authority should not include "MARKER-92-urlpw" // the warning itself must not echo the secret
    authority.toLowerCase should include("credential")
    // query-parameter form — the warn gate must fire here too (previously it did not)
    warnFor("jdbc:postgresql://host:5432/db?user=admin&password=MARKER-92-urlpw").toLowerCase should include("credential")
    // no credentials → no warning
    warnFor("jdbc:h2:mem:hikari_no_url_creds") shouldBe empty
  }

  "a config with no secrets" should "produce no warning" in {
    val cfg      = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:hikari_no_secrets")
    cfg.addDataSourceProperty("maxPoolQueries", "500")
    val warnings = LogCapture.capture(Seq(pluginLogger), Level.WARN) {
      JdbcProtocol.warnOnSecretProperties(cfg)
    }
    warnings shouldBe empty
  }
}
