package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import java.util.concurrent.Executors
import scala.util.Failure

/** Regression tests for issue #33: batch execution should use prepared statements instead of inlining raw quoted strings, so
  * that string values containing single-quotes are handled safely.
  */
class BatchPreparedStatementSpec extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {

  private var dataSource: HikariDataSource = _
  private var client: JDBCClient           = _

  override def beforeAll(): Unit = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:batchtest;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(4)
    dataSource = new HikariDataSource(cfg)

    val conn = dataSource.getConnection
    try {
      conn
        .createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS batch_items (
            |  id   INT PRIMARY KEY,
            |  name VARCHAR(255),
            |  flag BOOLEAN,
            |  val  DOUBLE
            |)""".stripMargin,
        )
    } finally conn.close()

    client = JDBCClient(dataSource, Executors.newFixedThreadPool(4))
  }

  override def afterAll(): Unit = {
    client.close()
  }

  private def clearTable(): Unit = {
    val conn = dataSource.getConnection
    try conn.createStatement().execute("DELETE FROM BATCH_ITEMS")
    finally conn.close()
  }

  private def countRows(): Int = {
    val conn = dataSource.getConnection
    try {
      val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM BATCH_ITEMS")
      rs.next()
      rs.getInt(1)
    } finally conn.close()
  }

  private def fetchName(id: Int): String = {
    val conn = dataSource.getConnection
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT name FROM batch_items WHERE id = $id")
      if (rs.next()) rs.getString(1) else null
    } finally conn.close()
  }

  "batch" should "insert rows with plain string values" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 1, "name" -> "hello", "flag" -> true, "val" -> 1.5)),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1)
      countRows() shouldBe 1
      fetchName(1) shouldBe "hello"
    }
  }

  it should "safely handle string values containing single-quotes (regression for #33)" in {
    clearTable()
    val dangerous = "O'Brien"
    val queries   = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 2, "name" -> dangerous, "flag" -> false, "val" -> 0.0)),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1)
      fetchName(2) shouldBe dangerous
    }
  }

  it should "safely handle string values containing double single-quotes" in {
    clearTable()
    val tricky  = "it''s a trap"
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 3, "name" -> tricky, "flag" -> false, "val" -> 0.0)),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1)
      fetchName(3) shouldBe tricky
    }
  }

  // withParamsMap treats the string literal "NULL" as a sentinel that maps to NullParam (SQL NULL).
  // This is a convention of the withParamsMap API: it is impossible to insert the literal four-character
  // string "NULL" via withParamsMap; use withParams(... -> NullParam) for explicit NULL, or
  // withParams(... -> StrParam("NULL")) if you genuinely need the string.
  it should "treat string literal 'NULL' as SQL NULL per withParamsMap convention" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 4, "name" -> "NULL", "flag" -> false, "val" -> 0.0)),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1)
      fetchName(4) shouldBe null
    }
  }

  it should "return Array.empty without error for an empty batch" in {
    client.batch(Seq.empty) { result =>
      result.success.value shouldBe Array.empty
    }
  }

  it should "handle UUID parameter type" in {
    clearTable()
    val conn = dataSource.getConnection
    try {
      conn
        .createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS batch_uuid_items (
            |  id   UUID PRIMARY KEY,
            |  name VARCHAR(255)
            |)""".stripMargin,
        )
    } finally conn.close()

    val id      = UUID.randomUUID()
    val queries = Seq(
      SQL("INSERT INTO batch_uuid_items (id, name) VALUES ({id},{name})")
        .withParamsMap(Map("id" -> id, "name" -> "uuid-test")),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1)

      val conn2 = dataSource.getConnection
      try {
        val rs = conn2.createStatement().executeQuery(s"SELECT name FROM batch_uuid_items WHERE id = '$id'")
        rs.next() shouldBe true
        rs.getString(1) shouldBe "uuid-test"
      } finally conn2.close()
    }
  }

  it should "handle multiple queries in a single batch" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 10, "name" -> "first", "flag" -> true, "val" -> 1.0)),
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 11, "name" -> "O'Reilly", "flag" -> false, "val" -> 2.0)),
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 12, "name" -> "third", "flag" -> true, "val" -> 3.0)),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1, 1, 1)
      countRows() shouldBe 3
      fetchName(11) shouldBe "O'Reilly"
    }
  }

  it should "return counts for each executed query" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 20, "name" -> "a", "flag" -> true, "val" -> 1.0)),
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 21, "name" -> "b", "flag" -> false, "val" -> 2.0)),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1, 1)
    }
  }

  it should "handle boolean and double values" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 30, "name" -> "booltest", "flag" -> true, "val" -> 3.14)),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1)
      countRows() shouldBe 1
    }
  }

  it should "rollback all changes when a batch query fails mid-way" in {
    clearTable()
    // Two SQL groups: first inserts row 40 (succeeds), second tries to insert a duplicate id 40 (fails with PK violation).
    // Both should be rolled back — table must remain empty after the failed batch.
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 40, "name" -> "first", "flag" -> true, "val" -> 1.0)),
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 40, "name" -> "duplicate-pk", "flag" -> false, "val" -> 2.0)),
    )
    client.batch(queries) { result =>
      result shouldBe a[Failure[_]]
      result.failure
      countRows() shouldBe 0
    }
  }
}
