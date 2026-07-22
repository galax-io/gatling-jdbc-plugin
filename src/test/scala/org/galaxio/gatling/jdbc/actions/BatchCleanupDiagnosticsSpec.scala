package org.galaxio.gatling.jdbc.actions

import ch.qos.logback.classic.Level
import io.gatling.commons.stats.KO
import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions.{BatchActionBuilder, BatchUpdateAction}
import org.galaxio.gatling.jdbc.db.testsupport.{FailingConnectionDataSource, H2, LogCapture}
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors

/** Action-level regression for issue #84 (US3), updated for #126: when a batch fails and its rollback also fails, an engineer
  * triaging the failure must still see BOTH the original database error and the fact that cleanup also failed. Under #126 the
  * stats/report message carries this structurally (each error's class + the "cleanup also failed" marker) with no raw driver
  * prose, while the full raw detail — including both exception messages — is on the plugin's DEBUG channel.
  */
class BatchCleanupDiagnosticsSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName    = "batch_cleanup_diagnostics"
  private val actionLog = "org.galaxio.gatling.jdbc.actions.ActionBase"

  "a batch whose execution and rollback both fail" should "report both failures structurally, with raw prose only on DEBUG" in {
    val failingDs = new FailingConnectionDataSource(H2.config(dbName, 2))
    val client    = JDBCClient(failingDs, Executors.newFixedThreadPool(2))
    val stats     = new RecordingStatsEngine
    val tc        = buildTestContextWithClient(client, config, stats)
    try {
      val conn = java.sql.DriverManager.getConnection(H2.jdbcUrl(dbName), "sa", "")
      try conn.createStatement().execute("CREATE TABLE IF NOT EXISTS cleanup_diag (id INT PRIMARY KEY)")
      finally conn.close()

      failingDs.failExecuteBatch = true
      failingDs.failRollback = true

      val capture = new CaptureAction()
      val lines   = LogCapture.capture(Seq(actionLog), Level.DEBUG) {
        BatchActionBuilder(
          _ => Success("cleanup-diag-batch"),
          Seq(BatchUpdateAction(_ => Success("cleanup_diag"), Seq("id" -> (_ => Success(1))), None)),
        ).build(tc.ctx, capture) ! freshSession()
        capture.awaitCapture() shouldBe true
      }

      capture.capturedSession.isFailed shouldBe true
      stats.responses should have size 1
      val response = stats.responses.head
      response.status shouldBe KO

      val message = response.message.getOrElse("")
      // structural: primary error class, the cleanup marker, and the cleanup error class — no raw prose
      message should include("BatchUpdateException") // primary cause (class)
      message should include("cleanup also failed")  // human-visible marker that cleanup failed too
      message should include("SQLException")         // the cleanup failure (class)
      message should not include "injected executeBatch failure"
      message should not include "injected rollback failure"
      message.length should be <= 512

      // raw prose relocated to the DEBUG channel — not lost, opt-in
      val rawLog = lines.mkString("\n")
      rawLog should include("injected executeBatch failure")
      rawLog should include("injected rollback failure")
    } finally {
      tc.close()
    }
  }
}
