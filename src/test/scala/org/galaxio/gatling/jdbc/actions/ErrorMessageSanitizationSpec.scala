package org.galaxio.gatling.jdbc.actions

import ch.qos.logback.classic.Level
import io.gatling.commons.stats.KO
import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions.{Columns, DBInsertActionBuilder}
import org.galaxio.gatling.jdbc.db.testsupport.LogCapture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression for issue #126 (spec 005 US2): a database error recorded into Gatling stats must be rebuilt from structured,
  * value-free fields (class, SQLState, vendor code) — never the raw driver message, which echoes feeder values (PII). The raw
  * message stays reachable only through the plugin's DEBUG logger.
  */
class ErrorMessageSanitizationSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName    = "error_sanitization"
  private val url       = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
  private val piiMarker = "pii-MARKER@x.io"
  private val logger    = "org.galaxio.gatling.jdbc.actions.ActionBase"

  private def seed(): Unit = {
    val conn = java.sql.DriverManager.getConnection(url, "sa", "")
    try {
      val st = conn.createStatement()
      st.execute("CREATE TABLE IF NOT EXISTS emails (email VARCHAR(255) PRIMARY KEY)")
      st.execute("DELETE FROM emails")
      st.execute(s"INSERT INTO emails (email) VALUES ('$piiMarker')")
    } finally conn.close()
  }

  /** Insert the same PK a second time → the driver raises a unique-constraint violation whose raw message contains the value.
    */
  private def runDuplicateInsert(): (RecordingStatsEngine, Seq[String]) = {
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    val lines = LogCapture.capture(Seq(logger), Level.DEBUG) {
      val capture = new CaptureAction()
      DBInsertActionBuilder(
        _ => Success("dup-insert-request"),
        _ => Success("emails"),
        Columns("email"),
        Seq("email" -> (_ => Success(piiMarker))),
      ).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true
    }
    try (stats, lines)
    finally tc.close()
  }

  "a database error recorded into stats" should "be structured and free of feeder values" in {
    seed()
    val (stats, _) = runDuplicateInsert()

    stats.responses should have size 1
    val message = stats.responses.head.message.getOrElse("")
    stats.responses.head.status shouldBe KO

    message should not include piiMarker
    message should fullyMatch regex """[\w.$]+ \[SQLState=[^,]*, code=-?\d+\].*"""
    message.length should be <= 512
  }

  "the raw driver message" should "be available only through the DEBUG logger" in {
    seed()
    val (_, lines) = runDuplicateInsert()

    // the raw text (with the value) is what an engineer opts into via DEBUG — proving it is not lost, only relocated
    lines.exists(_.contains(piiMarker)) shouldBe true
  }

  "an over-long KO message" should "be truncated to the bound and marked with an ellipsis" in {
    // a data-free plugin message longer than the 512-char bound; the cut must be visible (spec edge case: "clearly marked")
    val huge    =
      new IllegalStateException("cap " + ("x" * 600)) with org.galaxio.gatling.jdbc.db.SafeDiagnosticMessage
    val message = org.galaxio.gatling.jdbc.protocol.Redaction.koMessage(huge)

    message.length shouldBe 512
    message.last shouldBe '…'
  }
}
