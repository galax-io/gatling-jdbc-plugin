package org.galaxio.gatling.jdbc.javaapi

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation.Success
import org.galaxio.gatling.javaapi.JdbcDsl
import org.galaxio.gatling.javaapi.check.simpleCheckType
import org.galaxio.gatling.jdbc.actions.JdbcActionSpecFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression tests for issue #80: the Java QueryActionBuilder must be copy-on-write like every sibling javaapi builder —
  * deriving two branches from one shared base must leave the base and each branch independent.
  *
  * Before the fix, `.check(...)` reassigned the internal `wrapped` field and returned `this`, so all "branches" were the same
  * mutated object and the base accumulated every branch's checks: the tests below fail on the unfixed code.
  */
class QueryActionBuilderBranchSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private def scalaBuilderOf(
      b: org.galaxio.gatling.javaapi.actions.QueryActionBuilder,
  ): org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder =
    b.asScala().asInstanceOf[org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder]

  private def newBase(): org.galaxio.gatling.javaapi.actions.QueryActionBuilder =
    new org.galaxio.gatling.javaapi.actions.QueryActionBuilder(
      org.galaxio.gatling.jdbc.actions.actions
        .QueryActionBuilder(_ => Success("branch-request"), _ => Success("SELECT * FROM branch_items"), params = Seq.empty),
    )

  "javaapi QueryActionBuilder.check" should "return a new independent instance and leave the base unmodified" in {
    val base    = newBase()
    val branchA = base.check(JdbcDsl.simpleCheck(simpleCheckType.NonEmpty).asInstanceOf[Object])
    val branchB = base.check(JdbcDsl.simpleCheck(simpleCheckType.Empty).asInstanceOf[Object])

    withClue("check() must not return the receiver: ") {
      branchA should not be theSameInstanceAs(base)
      branchB should not be theSameInstanceAs(base)
      branchA should not be theSameInstanceAs(branchB)
    }
    withClue("the base builder must stay check-free after deriving branches: ") {
      scalaBuilderOf(base).checks shouldBe empty
    }
    scalaBuilderOf(branchA).checks should have size 1
    scalaBuilderOf(branchB).checks should have size 1
  }

  it should "keep two H2-backed branches' outcomes independent of build order" in {
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext("branch_items", 2, config, stats)

    try {
      val conn  = tc.client.asInstanceOf[org.galaxio.gatling.jdbc.db.JDBCClient]
      // seed one row so the table is non-empty
      val setup = java.sql.DriverManager.getConnection("jdbc:h2:mem:branch_items;DB_CLOSE_DELAY=-1", "sa", "")
      try {
        setup.createStatement().execute("CREATE TABLE IF NOT EXISTS branch_items (id INT PRIMARY KEY)")
        setup.createStatement().execute("DELETE FROM branch_items")
        setup.createStatement().execute("INSERT INTO branch_items (id) VALUES (1)")
      } finally setup.close()
      conn should not be null

      val base    = newBase()
      val branchA = base.check(JdbcDsl.simpleCheck(simpleCheckType.NonEmpty).asInstanceOf[Object]) // must pass
      val branchB = base.check(JdbcDsl.simpleCheck(simpleCheckType.Empty).asInstanceOf[Object])    // must fail

      val captureA = new CaptureAction()
      scalaBuilderOf(branchA).build(tc.ctx, captureA) ! freshSession()
      captureA.awaitCapture() shouldBe true
      withClue("branch A declared only the NonEmpty check and must pass: ") {
        captureA.capturedSession.isFailed shouldBe false
      }

      val captureB = new CaptureAction()
      scalaBuilderOf(branchB).build(tc.ctx, captureB) ! freshSession()
      captureB.awaitCapture() shouldBe true
      withClue("branch B declared only the Empty check and must fail: ") {
        captureB.capturedSession.isFailed shouldBe true
      }

      stats.responses.map(_.status) shouldBe List(OK, KO)
    } finally {
      tc.close()
    }
  }
}
