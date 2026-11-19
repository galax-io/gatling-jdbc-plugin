package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.OK
import io.gatling.commons.validation.Success
import io.gatling.core.session.Expression
import org.galaxio.gatling.jdbc.actions.actions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A where(clause, params*) clause whose string literal holds braces: the interpolator copies the literal as written, so the
  * construction-time guard must not take the braces inside it for a placeholder.
  */
class BatchWhereBracesInLiteralSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName = "batch_where_braces_literal"
  private val url    = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

  private def withFreshConnection[T](body: java.sql.Connection => T): T = {
    val conn = java.sql.DriverManager.getConnection(url, "sa", "")
    try body(conn)
    finally conn.close()
  }

  private def seed(): Unit = withFreshConnection { conn =>
    conn
      .createStatement()
      .execute("CREATE TABLE IF NOT EXISTS bwb_rows (id INT PRIMARY KEY, tags VARCHAR(50), name VARCHAR(50))")
    conn.createStatement().execute("DELETE FROM bwb_rows")
    conn.createStatement().execute("INSERT INTO bwb_rows (id, tags, name) VALUES (1, '{x,y}', 'KEEP'), (2, '{x,y}', 'KEEP')")
  }

  private def nameOf(id: Int): String = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery(s"SELECT name FROM bwb_rows WHERE id = $id")
    rs.next()
    rs.getString(1)
  }

  private def valuesStep(setValues: (String, Expression[Any])*): BatchUpdateValuesStepAction =
    BatchUpdateValuesStepAction(_ => Success("bwb_rows"), setValues)

  "a parameterized where clause with braces inside a string literal" should "be accepted and bind only the placeholder outside it" in {
    seed()
    val update =
      valuesStep("name" -> (_ => Success("CHANGED"))).where("tags = '{x,y}' AND id = {id}", "id" -> (_ => Success(1)))

    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      BatchActionBuilder(_ => Success("where-literal-batch"), Seq(update)).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      stats.responses.head.status shouldBe OK
    } finally tc.close()

    nameOf(1) shouldBe "CHANGED"
    nameOf(2) shouldBe "KEEP"
  }
}
