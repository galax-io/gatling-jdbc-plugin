package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

/** Regression spec for issue #86 (US4), client level: the discard path drains without retaining rows (SC-005 at the spec's
  * 1M-row scale), and the `maxRows` cap fails loud on overflow on both the discard and materializing paths.
  */
class BoundedRetrievalSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dbName       = "bounded_retrieval"
  private val dataSource   = H2.dataSource(dbName, 2)
  private val blockingPool = Executors.newFixedThreadPool(2)
  private val client       = JDBCClient(dataSource, blockingPool)

  override def afterAll(): Unit =
    client.close()

  private def discard(sql: String, maxRows: Option[Int]): Try[Long] =
    Await.result(client.executeSelectDiscard(sql, Seq.empty, maxRows)(identity), 60.seconds)

  private def select(sql: String, maxRows: Option[Int]): Try[List[Map[String, Any]]] =
    Await.result(client.executeSelect(sql, Seq.empty, maxRows)(identity), 60.seconds)

  "executeSelectDiscard" should "drain a 1,000,000-row result, returning the count and retaining no rows" in {
    discard("SELECT x FROM SYSTEM_RANGE(1, 1000000)", None).success.value shouldBe 1000000L
  }

  it should "enforce the maxRows cap while draining" in {
    val failure = discard("SELECT x FROM SYSTEM_RANGE(1, 11)", Some(10)).failure.exception

    failure.getMessage should include("maxRows")
    failure.getMessage should include("10")
  }

  it should "pass a result exactly at the cap" in {
    discard("SELECT x FROM SYSTEM_RANGE(1, 10)", Some(10)).success.value shouldBe 10L
  }

  it should "accept Int.MaxValue as a cap without overflowing" in {
    discard("SELECT x FROM SYSTEM_RANGE(1, 5)", Some(Int.MaxValue)).success.value shouldBe 5L
  }

  it should "reject duplicate labels before draining, even with no consumer of rows" in {
    val failure = discard("SELECT x, x FROM SYSTEM_RANGE(1, 3)", None).failure.exception

    failure shouldBe a[DuplicateColumnLabelException]
  }

  it should "count zero rows without error" in {
    discard("SELECT x FROM SYSTEM_RANGE(1, 10) WHERE x > 100", Some(5)).success.value shouldBe 0L
  }

  "executeSelect with maxRows" should "return all rows when the result is exactly at the cap" in {
    val rows = select("SELECT x FROM SYSTEM_RANGE(1, 10)", Some(10)).success.value

    rows should have size 10
  }

  it should "fail loud when the result exceeds the cap instead of truncating" in {
    val failure = select("SELECT x FROM SYSTEM_RANGE(1, 11)", Some(10)).failure.exception

    failure.getMessage should include("maxRows")
    failure.getMessage should include("10")
  }

  it should "return an empty result under a cap without error" in {
    select("SELECT x FROM SYSTEM_RANGE(1, 10) WHERE x > 100", Some(5)).success.value shouldBe empty
  }

  it should "behave exactly as the uncapped overload when maxRows is absent" in {
    select("SELECT x FROM SYSTEM_RANGE(1, 3)", None).success.value should have size 3
  }
}
