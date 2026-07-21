package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import org.galaxio.gatling.jdbc.db.testsupport.H2
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Failure

/** Regression spec for issue #88 (US1): an operation reported OK must be durably persisted, and pool configurations that
  * silently defeat that guarantee are rejected at startup.
  */
class AutoCommitGuardSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dbName       = "autocommit_guard"
  private val dataSource   = H2.dataSource(dbName, 2)
  private val blockingPool = Executors.newFixedThreadPool(2)
  private val client       = JDBCClient(dataSource, blockingPool)

  override def beforeAll(): Unit = {
    exec("CREATE TABLE IF NOT EXISTS guard_rows (id INT PRIMARY KEY, name VARCHAR(50))")
    exec("DELETE FROM guard_rows")
  }

  override def afterAll(): Unit =
    client.close()

  private def exec(sql: String): Unit =
    Await.result(client.executeRaw(sql)(identity), 10.seconds)

  /** Counts rows through a completely independent connection — proves visibility beyond the pool that wrote them. */
  private def countFromFreshConnection(table: String): Int = {
    val conn = DriverManager.getConnection(H2.jdbcUrl(dbName), "sa", "")
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT COUNT(*) FROM $table")
      rs.next() shouldBe true
      rs.getInt(1)
    } finally conn.close()
  }

  "JDBCClient.apply" should "reject a pool configured with autoCommit=false and name the supported alternatives" in {
    val cfg          = H2.config("autocommit_guard_reject", 2)
    cfg.setAutoCommit(false)
    val rejectedPool = new HikariDataSource(cfg)
    val pool         = Executors.newFixedThreadPool(1)
    try {
      val ex = the[IllegalArgumentException] thrownBy JDBCClient(rejectedPool, pool)
      ex.getMessage should include("auto-commit")
      ex.getMessage should include("batch")
      ex.getMessage should include("rawSql")
    } finally {
      rejectedPool.close()
      pool.shutdown()
    }
  }

  "executeUpdate" should "report OK only for changes visible to a fresh, independent connection" in {
    val result = Await.result(
      client.executeUpdate(
        "INSERT INTO guard_rows (id, name) VALUES ({id}, {name})",
        Seq("id" -> IntParam(1), "name" -> StrParam("alice")),
      )(identity),
      10.seconds,
    )
    result.success.value shouldBe 1
    countFromFreshConnection("guard_rows") shouldBe 1
  }

  "batch" should "commit successful batches so rows are visible to a fresh connection" in {
    exec("CREATE TABLE IF NOT EXISTS guard_batch_ok (id INT PRIMARY KEY)")
    exec("DELETE FROM guard_batch_ok")

    val queries = Seq(
      SQL("INSERT INTO guard_batch_ok (id) VALUES ({id})").withParams("id" -> IntParam(1)),
      SQL("INSERT INTO guard_batch_ok (id) VALUES ({id})").withParams("id" -> IntParam(2)),
    )
    Await.result(client.batch(queries)(_.success.value shouldBe Array(1, 1)), 10.seconds)
    countFromFreshConnection("guard_batch_ok") shouldBe 2
  }

  it should "roll back failed batches so no partial rows are visible" in {
    exec("CREATE TABLE IF NOT EXISTS guard_batch_ko (id INT PRIMARY KEY)")
    exec("DELETE FROM guard_batch_ko")

    val queries = Seq(
      SQL("INSERT INTO guard_batch_ko (id) VALUES ({id})").withParams("id" -> IntParam(1)),
      SQL("INSERT INTO guard_batch_ko (id) VALUES ({id})").withParams("id" -> IntParam(1)), // duplicate PK → batch fails
    )
    Await.result(client.batch(queries)(_ shouldBe a[Failure[_]]), 10.seconds)
    countFromFreshConnection("guard_batch_ko") shouldBe 0
  }
}
