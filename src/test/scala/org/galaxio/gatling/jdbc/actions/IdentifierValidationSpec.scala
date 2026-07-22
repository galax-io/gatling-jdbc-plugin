package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions.{
  BatchActionBuilder,
  BatchInsertAction,
  BatchUpdateAction,
  Columns,
  DBInsertActionBuilder,
}
import org.galaxio.gatling.jdbc.db.testsupport.LogCapture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End-to-end spec for issue #124 (US6): a malicious feeder-driven table name (and any invalid static column name) fails the
  * request through the crash KO path before any SQL is assembled — the target table stays intact; valid plain, qualified, and
  * quoted identifiers keep working. `where(...)` fragments stay un-validated by contract.
  */
class IdentifierValidationSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName = "identifier_validation"
  private val url    = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

  private def withFreshConnection[T](body: java.sql.Connection => T): T = {
    val conn = java.sql.DriverManager.getConnection(url, "sa", "")
    try body(conn)
    finally conn.close()
  }

  private def seed(): Unit = withFreshConnection { conn =>
    conn.createStatement().execute("CREATE TABLE IF NOT EXISTS id_rows (id INT PRIMARY KEY)")
    conn.createStatement().execute("""CREATE TABLE IF NOT EXISTS "Weird Name" (id INT PRIMARY KEY)""")
    conn.createStatement().execute("DELETE FROM id_rows")
    conn.createStatement().execute("""DELETE FROM "Weird Name"""")
    conn.createStatement().execute("INSERT INTO id_rows (id) VALUES (100)")
  }

  private def count(table: String): Int = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery(s"SELECT COUNT(*) FROM $table")
    rs.next()
    rs.getInt(1)
  }

  private def runInsert(tableName: String, columns: Columns, id: Int): (RecordingStatsEngine, Boolean) = {
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      DBInsertActionBuilder(
        _ => Success("identifier-request"),
        _ => Success(tableName),
        columns,
        Seq(columns.names.head -> (_ => Success(id))),
      ).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true
      (stats, capture.capturedSession.isFailed)
    } finally tc.close()
  }

  "a feeder-driven malicious table name" should "fail the request with a value-free crash message (#126)" in {
    seed()
    val malicious = "id_rows; DROP TABLE id_rows"
    val stats     = new RecordingStatsEngine
    val tc        = buildRealTestContext(dbName, 2, config, stats)
    val debug     = LogCapture.capture(Seq("org.galaxio.gatling.jdbc.actions.ActionBase")) {
      val capture = new CaptureAction()
      DBInsertActionBuilder(
        _ => Success("identifier-request"),
        _ => Success(malicious),
        Columns("id"),
        Seq("id" -> (_ => Success(1))),
      ).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe true
    }
    try {
      stats.crashes should have size 1
      // the feeder-derived value must NOT reach shared stats/reports (spec 005 FR-007) …
      stats.crashes.head.error should not include malicious
      stats.crashes.head.error should include("Invalid SQL identifier rejected")
      stats.responses.head.message.getOrElse("") should not include malicious
      stats.responses.head.status shouldBe KO
      // … while the full detail (with the value) is available to an engineer who enables DEBUG
      debug.mkString("\n") should include(malicious)

      count("id_rows") shouldBe 1 // seed row only — nothing executed, table intact
    } finally tc.close()
  }

  "an invalid static column name" should "fail the request before any SQL is assembled, value-free" in {
    seed()
    val (stats, failed) = runInsert("id_rows", Columns("id--"), 1)

    failed shouldBe true
    stats.crashes.head.error should not include "id--"
    stats.crashes.head.error should include("Invalid SQL identifier rejected")
    count("id_rows") shouldBe 1
  }

  "a valid plain identifier" should "execute exactly as before" in {
    seed()
    val (stats, failed) = runInsert("id_rows", Columns("id"), 7)

    failed shouldBe false
    stats.responses.head.status shouldBe OK
    count("id_rows") shouldBe 2
  }

  "a valid quoted identifier" should "execute per the documented quoting policy" in {
    seed()
    val (stats, failed) = runInsert(""""Weird Name"""", Columns("id"), 1)

    failed shouldBe false
    stats.responses.head.status shouldBe OK
    count("\"Weird Name\"") shouldBe 1
  }

  "a batch insert with a malicious table name" should "fail the request with the table intact" in {
    seed()
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      BatchActionBuilder(
        _ => Success("identifier-batch-insert"),
        Seq(
          BatchInsertAction(
            _ => Success("id_rows; DROP TABLE id_rows"),
            Columns("id"),
            Seq("id" -> (_ => Success(1))),
          ),
        ),
      ).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true

      capture.capturedSession.isFailed shouldBe true
      stats.crashes should have size 1
      count("id_rows") shouldBe 1
    } finally tc.close()
  }

  "a batch update with a malicious table name" should "fail the request with the table intact" in {
    seed()
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      BatchActionBuilder(
        _ => Success("identifier-batch"),
        Seq(
          BatchUpdateAction(
            _ => Success("id_rows; DROP TABLE id_rows"),
            Seq("id" -> (_ => Success(1))),
            None,
          ),
        ),
      ).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true

      capture.capturedSession.isFailed shouldBe true
      stats.crashes should have size 1
      count("id_rows") shouldBe 1
    } finally tc.close()
  }
}
