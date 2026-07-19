package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors

/** Regression tests for issue #82: batch execution must preserve the declared order of SQL operations.
  *
  * Before the fix, `batch` grouped queries with `groupBy(_.sql)` (an unordered HashMap), so non-contiguous occurrences of the
  * same SQL text were merged into one batch group and executed together — reordering them relative to the statements declared
  * between them and producing a different final database state.
  */
class BatchOrderSpec extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {

  private var dataSource: HikariDataSource          = _
  private var client: JDBCClient                    = _
  private var countingDs: PrepareCountingDataSource = _
  private var countingClient: JDBCClient            = _

  override def beforeAll(): Unit = {
    dataSource = testsupport.H2.dataSource("batchorder", 4)

    val conn = dataSource.getConnection
    try
      conn
        .createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS order_items (
            |  id  INT PRIMARY KEY,
            |  val VARCHAR(255)
            |)""".stripMargin,
        )
    finally conn.close()

    client = JDBCClient(dataSource, Executors.newFixedThreadPool(4))

    countingDs = new PrepareCountingDataSource(testsupport.H2.config("batchorder", 2))
    countingClient = JDBCClient(countingDs, Executors.newFixedThreadPool(2))
  }

  override def afterAll(): Unit = {
    client.close()
    countingClient.close()
  }

  private def clearTable(): Unit = {
    val conn = dataSource.getConnection
    try conn.createStatement().execute("DELETE FROM order_items")
    finally conn.close()
  }

  private def fetchVal(id: Int): String = {
    val conn = dataSource.getConnection
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT val FROM order_items WHERE id = $id")
      if (rs.next()) rs.getString(1) else null
    } finally conn.close()
  }

  private val insertSql = "INSERT INTO order_items (id, val) VALUES ({id},{val})"
  private val updateSql = "UPDATE order_items SET val = 'updated'"

  "batch" should "not merge non-contiguous occurrences of the same SQL across an intervening statement" in {
    clearTable()
    // Declared order: insert id=1 -> update ALL rows -> insert id=2 (same INSERT sql as the first).
    // groupBy(_.sql) merges both inserts into one group, so id=2 is either inserted before the
    // update (and wrongly updated) or id=1's insert is delayed past it — final state differs either way.
    val queries        = Seq(
      SQL(insertSql).withParamsMap(Map("id" -> 1, "val" -> "a")),
      SQL(updateSql).withParamsMap(Map.empty),
      SQL(insertSql).withParamsMap(Map("id" -> 2, "val" -> "b")),
    )
    val preparesBefore = countingDs.prepareCount
    countingClient.batch(queries) { result =>
      result.success.value shouldBe Array(1, 1, 1)
      fetchVal(1) shouldBe "updated"
      fetchVal(2) shouldBe "b"
      // README "Batch Operations": interleaved A,B,A runs as three groups, never two.
      countingDs.prepareCount - preparesBefore shouldBe 3
    }
  }

  it should "execute distinct SQL statements in declared order with per-statement counts in declared order" in {
    clearTable()
    val queries = Seq(
      SQL(insertSql).withParamsMap(Map("id" -> 10, "val" -> "x")),
      SQL("UPDATE order_items SET val = 'first-update' WHERE id = {id}").withParamsMap(Map("id" -> 10)),
      SQL("DELETE FROM order_items WHERE id = {id}").withParamsMap(Map("id" -> 10)),
    )
    client.batch(queries) { result =>
      // insert touches 1 row, update touches the row that must already exist, delete removes it —
      // any reordering yields a 0 somewhere.
      result.success.value shouldBe Array(1, 1, 1)
      fetchVal(10) shouldBe null
    }
  }

  it should "still batch contiguous identical SQL correctly" in {
    clearTable()
    val queries = Seq(
      SQL(insertSql).withParamsMap(Map("id" -> 21, "val" -> "a")),
      SQL(insertSql).withParamsMap(Map("id" -> 22, "val" -> "b")),
      SQL(insertSql).withParamsMap(Map("id" -> 23, "val" -> "c")),
    )
    client.batch(queries) { result =>
      result.success.value shouldBe Array(1, 1, 1)
      fetchVal(21) shouldBe "a"
      fetchVal(22) shouldBe "b"
      fetchVal(23) shouldBe "c"
    }
  }

  it should "share one PreparedStatement per contiguous run (efficiency half of the ordering contract)" in {
    clearTable()
    // 3 contiguous identical inserts + 1 update = 2 runs -> exactly 2 prepareStatement calls;
    // an implementation preparing per-statement would show 4.
    val queries        = Seq(
      SQL(insertSql).withParamsMap(Map("id" -> 31, "val" -> "a")),
      SQL(insertSql).withParamsMap(Map("id" -> 32, "val" -> "b")),
      SQL(insertSql).withParamsMap(Map("id" -> 33, "val" -> "c")),
      SQL(updateSql).withParamsMap(Map.empty),
    )
    val preparesBefore = countingDs.prepareCount
    countingClient.batch(queries) { result =>
      result.success.value shouldBe Array(1, 1, 1, 3)
      countingDs.prepareCount - preparesBefore shouldBe 2
    }
  }
}

/** Test-only HikariDataSource whose connections count `prepareStatement` invocations (FR-007 efficiency assert). */
final class PrepareCountingDataSource(cfg: HikariConfig) extends HikariDataSource(cfg) {
  private val prepares = new java.util.concurrent.atomic.AtomicInteger(0)

  def prepareCount: Int = prepares.get()

  override def getConnection: java.sql.Connection = {
    val real = super.getConnection
    java.lang.reflect.Proxy
      .newProxyInstance(
        classOf[java.sql.Connection].getClassLoader,
        Array(classOf[java.sql.Connection]),
        (_: AnyRef, method: java.lang.reflect.Method, args: Array[AnyRef]) => {
          if (method.getName == "prepareStatement") prepares.incrementAndGet()
          try method.invoke(real, args: _*)
          catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
        },
      )
      .asInstanceOf[java.sql.Connection]
  }
}
