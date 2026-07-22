package org.galaxio.gatling.jdbc.actions

import io.gatling.commons.stats.OK
import io.gatling.commons.validation.Success
import org.galaxio.gatling.jdbc.actions.actions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.annotation.nowarn

/** Regression for issue #125 (spec 005 US1): batch-update WHERE values are bound as data via the parameterized overloads — a
  * hostile value can only match rows, never widen the predicate. Provably unsafe plain strings (Gatling EL inside the clause)
  * are rejected at DSL-construction time, before any load starts; the opaque Expression overload stays as the deprecated escape
  * hatch.
  */
class BatchWhereParamSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName = "batch_where_param"
  private val url    = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

  private def withFreshConnection[T](body: java.sql.Connection => T): T = {
    val conn = java.sql.DriverManager.getConnection(url, "sa", "")
    try body(conn)
    finally conn.close()
  }

  private def seed(): Unit = withFreshConnection { conn =>
    conn.createStatement().execute("CREATE TABLE IF NOT EXISTS bw_rows (id INT PRIMARY KEY, age VARCHAR(50), name VARCHAR(50))")
    conn.createStatement().execute("DELETE FROM bw_rows")
    conn.createStatement().execute("INSERT INTO bw_rows (id, age, name) VALUES (1, '30', 'KEEP')")
    conn.createStatement().execute("INSERT INTO bw_rows (id, age, name) VALUES (2, '1 OR 1=1', 'KEEP')")
  }

  private def nameOf(id: Int): String = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery(s"SELECT name FROM bw_rows WHERE id = $id")
    rs.next()
    rs.getString(1)
  }

  private def runBatch(update: BatchAction): (RecordingStatsEngine, Boolean) = {
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      BatchActionBuilder(_ => Success("where-param-batch"), Seq(update)).build(tc.ctx, capture) ! freshSession()
      capture.awaitCapture() shouldBe true
      (stats, capture.capturedSession.isFailed)
    } finally tc.close()
  }

  private def valuesStep(setValues: (String, io.gatling.core.session.Expression[Any])*): BatchUpdateValuesStepAction =
    BatchUpdateValuesStepAction(_ => Success("bw_rows"), setValues)

  // ─── (a) injection value bound as data ───────────────────────────────────────

  "a hostile WHERE value bound via the parameterized overload" should "only match rows, never widen the predicate" in {
    seed()
    val update = valuesStep("name" -> (_ => Success("CHANGED")))
      .where("age = {age}", "age" -> (_ => Success("1 OR 1=1")))

    val (stats, failed) = runBatch(update)

    failed shouldBe false
    stats.responses.head.status shouldBe OK
    nameOf(2) shouldBe "CHANGED" // literal match only
    nameOf(1) shouldBe "KEEP"    // 1 OR 1=1 never became predicate text
  }

  // ─── (b) EL-bearing plain strings rejected at construction ───────────────────

  "a plain where string containing Gatling EL" should "be rejected at DSL construction, naming the parameterized overload" in {
    val ex = intercept[IllegalArgumentException] {
      valuesStep("name" -> (_ => Success("X"))).where("age = '#{age}'")
    }
    ex.getMessage should include("where(")
    ex.getMessage should include("#{")
  }

  "a parameterized where clause containing Gatling EL" should "be rejected at DSL construction" in {
    intercept[IllegalArgumentException] {
      valuesStep("name" -> (_ => Success("X"))).where("age = '#{age}' AND id = {id}", "id" -> (_ => Success(1)))
    }
  }

  // ─── (c) construction-time placeholder validation ────────────────────────────

  "a where parameter without a matching placeholder" should "be rejected at DSL construction" in {
    val ex = intercept[IllegalArgumentException] {
      valuesStep("name" -> (_ => Success("X")))
        .where("age = {age}", "age" -> (_ => Success("30")), "bogus" -> (_ => Success(1)))
    }
    ex.getMessage should include("bogus")
  }

  "a where parameter colliding with a SET value name" should "be rejected at DSL construction" in {
    val ex = intercept[IllegalArgumentException] {
      valuesStep("name" -> (_ => Success("X"))).where("name = {name}", "name" -> (_ => Success("Y")))
    }
    ex.getMessage should include("name")
  }

  "a clause placeholder with no matching parameter or SET value" should "be rejected at DSL construction, not at runtime" in {
    val ex = intercept[IllegalArgumentException] {
      valuesStep("name" -> (_ => Success("X"))).where("id = {id} AND tenant = {ghost}", "id" -> (_ => Success(1)))
    }
    ex.getMessage should include("ghost")
  }

  "a parameterized clause whose placeholder is bound by a SET value" should "be accepted at construction" in {
    // {name} resolves to the SET value, {id} to the where-param — both are bindable, so construction must succeed
    noException should be thrownBy {
      valuesStep("name" -> (_ => Success("CHANGED"))).where("id = {id} AND name = {name}", "id" -> (_ => Success(1)))
    }
  }

  // ─── (d) static EL-free clauses keep working ─────────────────────────────────

  "a static EL-free where clause" should "keep working unchanged" in {
    seed()
    val update          = valuesStep("name" -> (_ => Success("STATIC"))).where("id = 1")
    val (stats, failed) = runBatch(update)

    failed shouldBe false
    stats.responses.head.status shouldBe OK
    nameOf(1) shouldBe "STATIC"
    nameOf(2) shouldBe "KEEP"
  }

  // ─── (e) deprecated Expression overload still functions ──────────────────────

  "the deprecated Expression-based where overload" should "still function as the documented escape hatch" in {
    seed()
    val expr: io.gatling.core.session.Expression[String] = _ => Success("id = 2")
    val update                                           = valuesStep("name" -> (_ => Success("ESCAPE"))).where(expr): @nowarn("cat=deprecation")

    val (stats, failed) = runBatch(update)

    failed shouldBe false
    stats.responses.head.status shouldBe OK
    nameOf(2) shouldBe "ESCAPE"
    nameOf(1) shouldBe "KEEP"
  }
}
