package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.KO
import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions.{BatchActionBuilder, BatchUpdateAction}
import org.galaxio.gatling.jdbc.db.testsupport.{FailingConnectionDataSource, H2}
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors

/** Action-level regression for issue #84 (US3): when a batch fails and its rollback also fails, an engineer triaging the
  * simulation report must see BOTH the original database error and the fact that cleanup also failed — not just the primary
  * error's bare message with the cleanup detail silently trapped inside `getSuppressed` where nothing in this codebase (there
  * is no logger) ever surfaces it.
  */
class BatchCleanupDiagnosticsSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName = "batch_cleanup_diagnostics"

  "a batch whose execution and rollback both fail" should "report the primary error AND the cleanup failure in the KO message" in {
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
      BatchActionBuilder(
        _ => Success("cleanup-diag-batch"),
        Seq(BatchUpdateAction(_ => Success("cleanup_diag"), Seq("id" -> (_ => Success(1))), None)),
      ).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true

      capture.capturedSession.isFailed shouldBe true
      stats.responses should have size 1
      val response = stats.responses.head
      response.status shouldBe KO

      val message = response.message.getOrElse("")
      message should include("injected executeBatch failure") // primary cause
      message should include("cleanup also failed")           // human-visible marker that cleanup failed too
      message should include("injected rollback failure")     // the actual cleanup failure detail
    } finally {
      tc.close()
    }
  }
}
