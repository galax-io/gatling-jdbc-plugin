package org.galaxio.gatling.jdbc.javaapi

import io.gatling.commons.stats.OK
import io.gatling.commons.validation.Success
import org.galaxio.gatling.javaapi.actions.BatchUpdateBaseAction
import org.galaxio.gatling.jdbc.actions.JdbcActionSpecFixture
import org.galaxio.gatling.jdbc.actions.actions.{BatchActionBuilder, BatchUpdateAction}
import org.galaxio.gatling.jdbc.internal.BatchBase
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Map => JMap}

/** The Java/Kotlin `all()` of a batch update reaches the Scala `.all` carrier — an UPDATE with no WHERE clause — and
  * updates every row of the table when the batch runs on H2.
  */
class BatchUpdateAllFacadeSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  private val dbName = "batch_update_all_facade"
  private val url    = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

  private def withFreshConnection[T](body: java.sql.Connection => T): T = {
    val conn = java.sql.DriverManager.getConnection(url, "sa", "")
    try body(conn)
    finally conn.close()
  }

  private def seed(): Unit = withFreshConnection { conn =>
    conn.createStatement().execute("CREATE TABLE IF NOT EXISTS ua_rows (id INT PRIMARY KEY, name VARCHAR(50))")
    conn.createStatement().execute("DELETE FROM ua_rows")
    conn.createStatement().execute("INSERT INTO ua_rows (id, name) VALUES (1, 'FIRST')")
    conn.createStatement().execute("INSERT INTO ua_rows (id, name) VALUES (2, 'SECOND')")
  }

  private def names(): List[String] = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery("SELECT name FROM ua_rows ORDER BY id")
    Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
  }

  private def allViaJavaFacade(): org.galaxio.gatling.javaapi.actions.BatchUpdateAction =
    new BatchUpdateBaseAction(new org.galaxio.gatling.jdbc.actions.actions.BatchUpdateBaseAction(_ => Success("ua_rows")))
      .set(JMap.of("name", "CHANGED"))
      .all()

  "FR-002: the Java all() of a batch update" should "unwrap to the Scala BatchUpdateAction without a where clause" in {
    BatchBase.toScalaBatch(allViaJavaFacade()) match {
      case update: BatchUpdateAction =>
        update.where shouldBe None
        update.updateValues.map(_._1) shouldBe Seq("name")
      case other                     => fail(s"expected a BatchUpdateAction, got $other")
    }
  }

  it should "update every row of the table when the batch runs" in {
    seed()
    val stats = new RecordingStatsEngine
    val tc    = buildRealTestContext(dbName, 2, config, stats)
    try {
      val capture = new CaptureAction()
      val update  = BatchBase.toScalaBatch(allViaJavaFacade())
      BatchActionBuilder(_ => Success("all-batch"), Seq(update)).build(tc.ctx, capture) ! freshSession()

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      stats.responses.head.status shouldBe OK
    } finally tc.close()

    names() shouldBe List("CHANGED", "CHANGED")
  }
}
