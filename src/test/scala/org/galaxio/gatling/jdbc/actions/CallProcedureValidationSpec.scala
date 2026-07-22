package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation.Success
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression for issue #90 (spec 005 US1): a session-resolved procedure name is validated against the SqlIdentifier grammar
  * before any CALL text is assembled — a hostile name fails the request through the crash KO path with nothing sent to the
  * database, while valid plain, schema-qualified, and quoted names keep executing against real H2 procedures (aliases).
  */
class CallProcedureValidationSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName = "call_proc_validation"
  private val url    = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

  private def withFreshConnection[T](body: java.sql.Connection => T): T = {
    val conn = java.sql.DriverManager.getConnection(url, "sa", "")
    try body(conn)
    finally conn.close()
  }

  private def seed(): Unit = withFreshConnection { conn =>
    conn.createStatement().execute("CREATE TABLE IF NOT EXISTS call_rows (id INT PRIMARY KEY)")
    conn.createStatement().execute("DELETE FROM call_rows")
    conn.createStatement().execute("INSERT INTO call_rows (id) VALUES (100)")
    // real H2 "stored procedures": aliases to a unique static JDK method (Integer.bitCount is not overloaded)
    conn.createStatement().execute("""CREATE ALIAS IF NOT EXISTS bit_p FOR "java.lang.Integer.bitCount"""")
    conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS callq")
    conn.createStatement().execute("""CREATE ALIAS IF NOT EXISTS callq.bit_q FOR "java.lang.Integer.bitCount"""")
  }

  private def rowCount: Int = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM call_rows")
    rs.next()
    rs.getInt(1)
  }

  private def runCall(procedureName: String): (RecordingStatsEngine, Boolean) = {
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      val action  = DBCallAction(
        requestName = _ => Success("call-validation-request"),
        procedureName = _ => Success(procedureName),
        next = capture,
        ctx = tc.ctx,
        sessionParams = Seq("v" -> (_ => Success(5))),
        outParams = Seq.empty,
      )
      action.execute(freshSession())
      capture.awaitCapture() shouldBe true
      (stats, capture.capturedSession.isFailed)
    } finally tc.close()
  }

  "a session-resolved hostile procedure name" should "fail the request with nothing sent to the database" in {
    seed()
    val (stats, failed) = runCall("call_rows; DROP TABLE call_rows")

    failed shouldBe true
    stats.crashes should have size 1 // crash KO path — rejected before SQL assembly, not a driver error
    stats.crashes.head.error should not be empty
    // the feeder-derived injection payload must NOT reach shared stats/reports (spec 005 FR-007)
    stats.crashes.head.error should not include "DROP TABLE"
    stats.crashes.head.error should include("Invalid SQL identifier rejected")
    stats.responses should have size 1
    stats.responses.head.status shouldBe KO

    rowCount shouldBe 1 // seed row only — the injected DROP never executed
  }

  "a valid plain procedure name" should "execute against H2 and report OK" in {
    seed()
    val (stats, failed) = runCall("bit_p")

    failed shouldBe false
    stats.crashes shouldBe empty
    stats.responses should have size 1
    stats.responses.head.status shouldBe OK
  }

  "a valid schema-qualified procedure name" should "execute against H2 and report OK" in {
    seed()
    val (stats, failed) = runCall("callq.bit_q")

    failed shouldBe false
    stats.crashes shouldBe empty
    stats.responses.head.status shouldBe OK
  }

  "a valid quoted procedure name" should "execute against H2 and report OK" in {
    seed()
    val (stats, failed) = runCall("\"BIT_P\"") // unquoted aliases fold to upper case in H2; quoting must stay usable

    failed shouldBe false
    stats.crashes shouldBe empty
    stats.responses.head.status shouldBe OK
  }
}
