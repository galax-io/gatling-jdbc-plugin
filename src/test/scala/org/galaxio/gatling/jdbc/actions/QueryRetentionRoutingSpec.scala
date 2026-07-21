package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder
import org.galaxio.gatling.jdbc.internal.JdbcCheck.simpleCheck
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression spec for issue #86 (US4), action level: queries without checks route through the discard path (no retention,
  * timing still reported), the `maxRows` cap is enforced on every path — never silently ignored — and queries with checks
  * behave exactly as before.
  */
class QueryRetentionRoutingSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName = "retention_routing"

  private def seed(rows: Int): Unit = {
    val conn = java.sql.DriverManager.getConnection(s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", "sa", "")
    try {
      conn.createStatement().execute("CREATE TABLE IF NOT EXISTS routing_rows (id INT PRIMARY KEY)")
      conn.createStatement().execute("DELETE FROM routing_rows")
      (1 to rows).foreach(i => conn.createStatement().execute(s"INSERT INTO routing_rows (id) VALUES ($i)"))
    } finally conn.close()
  }

  private def builder(maxRows: Option[Int], checks: Seq[org.galaxio.gatling.jdbc.JdbcCheck]): QueryActionBuilder = {
    val base = QueryActionBuilder(
      _ => Success("routing-request"),
      _ => Success("SELECT id FROM routing_rows"),
      params = Seq.empty,
      checks = checks,
    )
    maxRows.fold(base)(base.maxRows)
  }

  private def run(maxRows: Option[Int], checks: Seq[org.galaxio.gatling.jdbc.JdbcCheck]): (RecordingStatsEngine, Boolean) = {
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      builder(maxRows, checks).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true
      (stats, capture.capturedSession.isFailed)
    } finally tc.close()
  }

  "a query without checks" should "execute through the discard path and report OK with timing" in {
    seed(50)
    val (stats, failed) = run(maxRows = None, checks = Seq.empty)

    failed shouldBe false
    stats.responses should have size 1
    stats.responses.head.status shouldBe OK
    stats.responses.head.requestName shouldBe "routing-request"
  }

  it should "still enforce maxRows — the cap is never silently ignored" in {
    seed(11)
    val (stats, failed) = run(maxRows = Some(10), checks = Seq.empty)

    failed shouldBe true
    stats.responses.head.status shouldBe KO
    stats.responses.head.message.getOrElse("") should include("maxRows")
  }

  it should "pass under the cap" in {
    seed(10)
    val (_, failed) = run(maxRows = Some(10), checks = Seq.empty)

    failed shouldBe false
  }

  "a query with checks" should "deliver every row to the checks exactly as before" in {
    seed(7)
    val (stats, failed) = run(maxRows = None, checks = Seq(simpleCheck(_.size == 7)))

    failed shouldBe false
    stats.responses.head.status shouldBe OK
  }

  it should "fail loud when the result exceeds maxRows instead of truncating check input" in {
    seed(11)
    val (stats, failed) = run(maxRows = Some(10), checks = Seq(simpleCheck(_.nonEmpty)))

    failed shouldBe true
    stats.responses.head.status shouldBe KO
    stats.responses.head.message.getOrElse("") should include("maxRows")
  }
}
