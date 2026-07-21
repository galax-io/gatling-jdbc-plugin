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

/** Regression spec for issue #123 (US2): a result containing the same label twice must never silently drop a value — the
  * operation fails with an explicit error naming the duplicated label(s), before any row is mapped.
  */
class DuplicateColumnLabelSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dbName       = "dup_label_spec"
  private val dataSource   = H2.dataSource(dbName, 2)
  private val blockingPool = Executors.newFixedThreadPool(2)
  private val client       = JDBCClient(dataSource, blockingPool)

  override def beforeAll(): Unit = {
    exec("CREATE TABLE IF NOT EXISTS dup_a (id INT PRIMARY KEY, name VARCHAR(50))")
    exec("CREATE TABLE IF NOT EXISTS dup_b (id INT PRIMARY KEY, a_id INT)")
    exec("DELETE FROM dup_a")
    exec("DELETE FROM dup_b")
    exec("INSERT INTO dup_a (id, name) VALUES (1, 'alice')")
    exec("INSERT INTO dup_b (id, a_id) VALUES (10, 1)")
  }

  override def afterAll(): Unit =
    client.close()

  private def exec(sql: String): Unit =
    Await.result(client.executeRaw(sql)(identity), 10.seconds)

  private def select(sql: String): Try[List[Map[String, Any]]] =
    Await.result(client.executeSelect(sql, Seq.empty)(identity), 10.seconds)

  "executeSelect" should "fail a JOIN that yields the same label twice, naming the duplicate" in {
    val result = select("SELECT dup_a.id, dup_b.id FROM dup_a JOIN dup_b ON dup_b.a_id = dup_a.id")

    val failure = result.failure.exception
    failure shouldBe a[DuplicateColumnLabelException]
    failure.getMessage should include("ID")
    failure.getMessage should include("alias")
  }

  it should "succeed for the same JOIN once every column is aliased uniquely" in {
    val rows =
      select("SELECT dup_a.id AS a_id, dup_b.id AS b_id FROM dup_a JOIN dup_b ON dup_b.a_id = dup_a.id").success.value

    rows should have size 1
    rows.head.keySet shouldBe Set("A_ID", "B_ID")
    rows.head("A_ID") shouldBe 1
    rows.head("B_ID") shouldBe 10
  }

  it should "reject an alias that collides with another column's label" in {
    // name AS id → labels ID, ID after H2 upper-casing: uniqueness is judged on final labels
    val result = select("SELECT id, name AS id FROM dup_a")

    result.failure.exception shouldBe a[DuplicateColumnLabelException]
  }
}
