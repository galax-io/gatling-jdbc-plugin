package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import org.galaxio.gatling.jdbc.db.testsupport.{FailingConnectionDataSource, H2, H2ClientSpecFixture}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.BatchUpdateException
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

/** Regression spec for issue #84 (US3): a failed batch always reports the primary execution exception; rollback/close failures
  * ride along as suppressed, and a failed rollback never turns into a partial commit.
  */
class BatchCleanupSuppressionSpec extends AnyFlatSpec with Matchers with H2ClientSpecFixture with BeforeAndAfterEach {

  override protected val dbName = "cleanup_suppression"

  private val failingDataSource                              = new FailingConnectionDataSource(H2.config(dbName, 2))
  override protected def buildDataSource(): HikariDataSource = failingDataSource

  override def beforeAll(): Unit =
    exec("CREATE TABLE IF NOT EXISTS cs_rows (id INT PRIMARY KEY)")

  override def beforeEach(): Unit = {
    failingDataSource.reset()
    exec("DELETE FROM cs_rows")
  }

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

  "batch" should "keep the execution failure primary when rollback also fails, and persist nothing" in {
    failingDataSource.failRollback = true

    val failure = failingBatch().failure.exception
    failure shouldBe a[BatchUpdateException]
    failure.getSuppressed.map(_.getMessage) should contain("injected rollback failure")

    countFromFreshConnection("cs_rows") shouldBe 0
  }

  it should "keep the execution failure primary when rollback and connection close both fail" in {
    failingDataSource.failRollback = true
    failingDataSource.failConnectionClose = true

    val failure = failingBatch().failure.exception
    failure shouldBe a[BatchUpdateException]
    failure.getSuppressed.map(_.getMessage) should contain("injected rollback failure")
    failure.getSuppressed.map(_.getMessage) should contain("injected connection close failure")

    countFromFreshConnection("cs_rows") shouldBe 0
  }

  it should "keep the execution failure primary when the statement close fails" in {
    failingDataSource.failStatementClose = true

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

    countFromFreshConnection("cs_rows") shouldBe 0
  }

  it should "report the execution failure alone when cleanup succeeds" in {
    val failure = failingBatch().failure.exception

    failure shouldBe a[BatchUpdateException]
    failure.getSuppressed.map(_.getMessage) should not contain "injected rollback failure"

    countFromFreshConnection("cs_rows") shouldBe 0
  }

  "executeUpdate" should "keep the execution failure primary when the statement close fails" in {
    exec("INSERT INTO cs_rows (id) VALUES (5)")
    failingDataSource.failStatementClose = true

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
