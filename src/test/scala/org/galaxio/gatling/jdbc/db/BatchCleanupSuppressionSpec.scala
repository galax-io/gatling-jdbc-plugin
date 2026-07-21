package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.{FailingConnectionDataSource, H2}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{BatchUpdateException, DriverManager}
import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

/** Regression spec for issue #84 (US3): a failed batch always reports the primary execution exception; rollback/close failures
  * ride along as suppressed, and a failed rollback never turns into a partial commit.
  */
class BatchCleanupSuppressionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  private val dbName       = "cleanup_suppression"
  private val dataSource   = new FailingConnectionDataSource(H2.config(dbName, 2))
  private val blockingPool = Executors.newFixedThreadPool(2)
  private val client       = JDBCClient(dataSource, blockingPool)

  override def beforeAll(): Unit =
    exec("CREATE TABLE IF NOT EXISTS cs_rows (id INT PRIMARY KEY)")

  override def beforeEach(): Unit = {
    dataSource.reset()
    exec("DELETE FROM cs_rows")
  }

  override def afterAll(): Unit =
    client.close()

  private def exec(sql: String): Unit =
    Await.result(client.executeRaw(sql)(identity), 10.seconds)

  /** Two contiguous SQL runs: the first insert succeeds, the second fails on a duplicate key inside the transaction. */
  private def failingBatch(): Try[Array[Int]] = Await.result(
    client.batch(
      Seq(
        SQL("INSERT INTO cs_rows (id) VALUES ({a})").withParams("a" -> IntParam(1)),
        SQL("INSERT INTO cs_rows (id) VALUES ({b})").withParams("b" -> IntParam(1)),
      ),
    )(identity),
    10.seconds,
  )

  private def countFromFreshConnection(): Int = {
    val conn = DriverManager.getConnection(H2.jdbcUrl(dbName), "sa", "")
    try {
      val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM cs_rows")
      rs.next() shouldBe true
      rs.getInt(1)
    } finally conn.close()
  }

  "batch" should "keep the execution failure primary when rollback also fails, and persist nothing" in {
    dataSource.failRollback = true

    val failure = failingBatch().failure.exception
    failure shouldBe a[BatchUpdateException]
    failure.getSuppressed.map(_.getMessage) should contain("injected rollback failure")

    countFromFreshConnection() shouldBe 0
  }

  it should "keep the execution failure primary when rollback and connection close both fail" in {
    dataSource.failRollback = true
    dataSource.failConnectionClose = true

    val failure = failingBatch().failure.exception
    failure shouldBe a[BatchUpdateException]
    failure.getSuppressed.map(_.getMessage) should contain("injected rollback failure")
    failure.getSuppressed.map(_.getMessage) should contain("injected connection close failure")

    countFromFreshConnection() shouldBe 0
  }

  it should "keep the execution failure primary when the statement close fails" in {
    dataSource.failStatementClose = true

    // Single contiguous SQL run: the one-shot close flag must fire on the statement whose executeBatch failed,
    // not on an earlier successful group's close.
    val failure = Await
      .result(
        client.batch(
          Seq(
            SQL("INSERT INTO cs_rows (id) VALUES ({a})").withParams("a" -> IntParam(1)),
            SQL("INSERT INTO cs_rows (id) VALUES ({a})").withParams("a" -> IntParam(1)),
          ),
        )(identity),
        10.seconds,
      )
      .failure
      .exception

    failure shouldBe a[BatchUpdateException]
    failure.getSuppressed.map(_.getMessage) should contain("injected statement close failure")

    countFromFreshConnection() shouldBe 0
  }

  it should "report the execution failure alone when cleanup succeeds" in {
    val failure = failingBatch().failure.exception

    failure shouldBe a[BatchUpdateException]
    failure.getSuppressed.map(_.getMessage) should not contain "injected rollback failure"

    countFromFreshConnection() shouldBe 0
  }

  "executeUpdate" should "keep the execution failure primary when the statement close fails" in {
    exec("INSERT INTO cs_rows (id) VALUES (5)")
    dataSource.failStatementClose = true

    val failure = Await
      .result(
        client.executeUpdate("INSERT INTO cs_rows (id) VALUES ({id})", Seq("id" -> IntParam(5)))(identity),
        10.seconds,
      )
      .failure
      .exception

    failure.getMessage should not include "injected statement close failure"
    failure.getSuppressed.map(_.getMessage) should contain("injected statement close failure")
  }
}
