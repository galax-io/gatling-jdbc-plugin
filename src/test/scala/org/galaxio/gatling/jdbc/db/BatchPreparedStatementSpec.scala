package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/** Regression tests for issue #33: batch execution should use prepared statements
  * instead of inlining raw quoted strings, so that string values containing
  * single-quotes are handled safely.
  */
class BatchPreparedStatementSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

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

  private def await[T](block: (T => Unit, Throwable => Unit) => Unit): Either[Throwable, T] = {
    val latch  = new CountDownLatch(1)
    var result = Option.empty[Either[Throwable, T]]
    block(
      v => { result = Some(Right(v)); latch.countDown() },
      e => { result = Some(Left(e)); latch.countDown() },
    )
    latch.await(5, TimeUnit.SECONDS) shouldBe true
    result.getOrElse(Left(new RuntimeException("no result")))
  }

  private def clearTable(): Unit = {
    val conn = dataSource.getConnection
    try conn.createStatement().execute("DELETE FROM batch_items")
    finally conn.close()
  }

  private def countRows(): Int = {
    val conn = dataSource.getConnection
    try {
      val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM batch_items")
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
    val result = await[Array[Int]](client.batch(queries))
    result shouldBe a[Right[_, _]]
    countRows() shouldBe 1
    fetchName(1) shouldBe "hello"
  }

  it should "safely handle string values containing single-quotes (regression for #33)" in {
    clearTable()
    val dangerous = "O'Brien"
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 2, "name" -> dangerous, "flag" -> false, "val" -> 0.0)),
    )
    val result = await[Array[Int]](client.batch(queries))
    result shouldBe a[Right[_, _]]
    fetchName(2) shouldBe dangerous
  }

  it should "safely handle string values containing double single-quotes" in {
    clearTable()
    val tricky = "it''s a trap"
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 3, "name" -> tricky, "flag" -> false, "val" -> 0.0)),
    )
    val result = await[Array[Int]](client.batch(queries))
    result shouldBe a[Right[_, _]]
    fetchName(3) shouldBe tricky
  }

  it should "handle NULL values" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 4, "name" -> "NULL", "flag" -> false, "val" -> 0.0)),
    )
    val result = await[Array[Int]](client.batch(queries))
    result shouldBe a[Right[_, _]]
    fetchName(4) shouldBe null
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
    val result = await[Array[Int]](client.batch(queries))
    result shouldBe a[Right[_, _]]
    countRows() shouldBe 3
    fetchName(11) shouldBe "O'Reilly"
  }

  it should "return counts for each executed query" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 20, "name" -> "a", "flag" -> true, "val" -> 1.0)),
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 21, "name" -> "b", "flag" -> false, "val" -> 2.0)),
    )
    val result = await[Array[Int]](client.batch(queries))
    result.map(_.toList) shouldBe Right(List(1, 1))
  }

  it should "handle boolean and double values" in {
    clearTable()
    val queries = Seq(
      SQL("INSERT INTO batch_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})")
        .withParamsMap(Map("id" -> 30, "name" -> "booltest", "flag" -> true, "val" -> 3.14)),
    )
    val result = await[Array[Int]](client.batch(queries))
    result shouldBe a[Right[_, _]]
    countRows() shouldBe 1
  }
}
