package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.galaxio.gatling.jdbc.db.statements._
import org.galaxio.gatling.jdbc.db.testsupport.RecordingStatementProxy
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Types
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Failure

/** Regression tests for issue #120: PreparedStatement parameter setters must never be launched concurrently.
  *
  * The pre-#59 implementation created one eager Future per setter, so two parameters of one statement could be bound
  * concurrently on a multi-thread executor. These tests pin the fixed behavior with the instrumentation the issue prescribes: a
  * recording proxy around a real H2 statement asserts that at most one indexed parameter call is in progress at any instant and
  * that every index is bound exactly once with its declared value, while the H2 round-trip verifies the values the database
  * actually received.
  */
class StatementParamsConcurrencySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private var dataSource: HikariDataSource = _
  private var client: JDBCClient           = _

  override def beforeAll(): Unit = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:param_concurrency_test;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(4)
    dataSource = new HikariDataSource(cfg)

    val conn = dataSource.getConnection
    try {
      conn
        .createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS param_items (
            |  id   INT PRIMARY KEY,
            |  name VARCHAR(255),
            |  flag BOOLEAN,
            |  val  DOUBLE,
            |  uid  VARCHAR(64)
            |)""".stripMargin,
        )
      conn
        .createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS dup_items (
            |  id    INT PRIMARY KEY,
            |  a_val INT,
            |  b_val VARCHAR(255)
            |)""".stripMargin,
        )
      conn
        .createStatement()
        .execute(
          """CREATE ALIAS IF NOT EXISTS ECHO_FN AS $$
            |String echoFn(String s, long n) {
            |    return s + ":" + n;
            |}
            |$$""".stripMargin,
        )
      conn
        .createStatement()
        .execute(
          """CREATE ALIAS IF NOT EXISTS FORTY_TWO AS $$
            |long fortyTwo() {
            |    return 42L;
            |}
            |$$""".stripMargin,
        )
    } finally conn.close()

    client = JDBCClient(dataSource, Executors.newFixedThreadPool(4))
  }

  override def afterAll(): Unit =
    client.close()

  private def clearTables(): Unit = {
    val conn = dataSource.getConnection
    try {
      conn.createStatement().execute("DELETE FROM param_items")
      conn.createStatement().execute("DELETE FROM dup_items")
    } finally conn.close()
  }

  behavior of "PreparedStatement parameter binding (issue #120)"

  it should "bind a multi-parameter statement with no overlapping setter calls, each index exactly once" in {
    clearTables()
    val sql  = "INSERT INTO param_items (id, name, flag, val, uid) VALUES ({id},{name},{flag},{val},{uid})"
    val ctx  = Interpolator.interpolate(sql)
    val conn = dataSource.getConnection
    try {
      val (stmt, recorder) = RecordingStatementProxy.prepared(conn.prepareStatement(ctx.queryString))
      stmt.setParams(
        ctx,
        Map(
          "id"   -> IntParam(1),
          "name" -> StrParam("alice"),
          "flag" -> BooleanParam(true),
          "val"  -> DoubleParam(1.5),
          "uid"  -> NullParam,
        ),
      )
      stmt.executeUpdate() shouldBe 1

      recorder.maxConcurrentParamCalls shouldBe 1
      recorder.setterCountsByIndex shouldBe ctx.m.values.flatten.map(_ -> 1).toMap
      recorder.registerCountsByIndex shouldBe empty
      recorder.boundValueAt(ctx.m("id").head) shouldBe Integer.valueOf(1)
      recorder.boundValueAt(ctx.m("name").head) shouldBe "alice"
      recorder.boundValueAt(ctx.m("flag").head) shouldBe java.lang.Boolean.TRUE
      recorder.boundValueAt(ctx.m("val").head) shouldBe java.lang.Double.valueOf(1.5)
      recorder.boundValueAt(ctx.m("uid").head) shouldBe recorder.NullValue

      val rs = conn.createStatement().executeQuery("SELECT name, flag, val, uid FROM param_items WHERE id = 1")
      rs.next() shouldBe true
      rs.getString(1) shouldBe "alice"
      rs.getBoolean(2) shouldBe true
      rs.getDouble(3) shouldBe 1.5
      rs.getString(4) shouldBe null
    } finally conn.close()
  }

  it should "bind every index of a duplicated placeholder exactly once" in {
    clearTables()
    val sql  = "INSERT INTO dup_items (id, a_val, b_val) VALUES ({a},{a},{b})"
    val ctx  = Interpolator.interpolate(sql)
    val conn = dataSource.getConnection
    try {
      val (stmt, recorder) = RecordingStatementProxy.prepared(conn.prepareStatement(ctx.queryString))
      stmt.setParams(ctx, Map("a" -> IntParam(7), "b" -> StrParam("dup")))
      stmt.executeUpdate() shouldBe 1

      ctx.m("a") should have size 2
      recorder.maxConcurrentParamCalls shouldBe 1
      recorder.setterCountsByIndex shouldBe Map(1 -> 1, 2 -> 1, 3 -> 1)

      val rs = conn.createStatement().executeQuery("SELECT a_val, b_val FROM dup_items WHERE id = 7")
      rs.next() shouldBe true
      rs.getInt(1) shouldBe 7
      rs.getString(2) shouldBe "dup"
    } finally conn.close()
  }

  it should "prepare a zero-parameter statement without any tracked setter calls" in {
    clearTables()
    val ctx  = Interpolator.interpolate("INSERT INTO param_items (id, name) VALUES (3, 'fixed')")
    val conn = dataSource.getConnection
    try {
      val (stmt, recorder) = RecordingStatementProxy.prepared(conn.prepareStatement(ctx.queryString))
      stmt.setParams(ctx, Map.empty)
      stmt.executeUpdate() shouldBe 1

      recorder.maxConcurrentParamCalls shouldBe 0
      recorder.setterCountsByIndex shouldBe empty
    } finally conn.close()
  }

  it should "fail the whole operation with the original exception when a placeholder has no binding" in {
    clearTables()
    val future = client.executeUpdate(
      "INSERT INTO param_items (id, name) VALUES ({id},{name})",
      Seq("id" -> IntParam(4)), // {name} intentionally unbound
    )(identity)

    val result = Await.result(future, 10.seconds)
    result shouldBe a[Failure[_]]
    result.failure.exception shouldBe a[NoSuchElementException]

    val conn = dataSource.getConnection
    try {
      val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM param_items WHERE id = 4")
      rs.next() shouldBe true
      rs.getInt(1) shouldBe 0 // statement was never executed
    } finally conn.close()
  }

  it should "detect overlapping parameter calls (instrumentation sensitivity check)" in {
    val ctx  = Interpolator.interpolate("SELECT id FROM param_items WHERE id = {a} AND val = {b}")
    val conn = dataSource.getConnection
    try {
      val (stmt, recorder) = RecordingStatementProxy.prepared(conn.prepareStatement(ctx.queryString), holdMillis = 250)
      val ready            = new CountDownLatch(2)
      val done             = new CountDownLatch(2)
      val pool             = Executors.newFixedThreadPool(2)

      // Deliberately violate the contract: bind two parameters from two threads at once.
      // The recorder must observe the overlap — this is what makes the maxConcurrent == 1
      // assertions above meaningful.
      pool.execute { () =>
        ready.countDown(); ready.await(5, TimeUnit.SECONDS)
        stmt.setInt(1, 1); done.countDown()
      }
      pool.execute { () =>
        ready.countDown(); ready.await(5, TimeUnit.SECONDS)
        stmt.setInt(2, 2); done.countDown()
      }

      done.await(5, TimeUnit.SECONDS) shouldBe true
      pool.shutdown()

      recorder.maxConcurrentParamCalls shouldBe 2
    } finally conn.close()
  }

  // Client-level 50-user value-correctness for prepared statements lives in
  // PostgreSQLIntegrationSpec ("bind every parameter of concurrent multi-param inserts...").

  // ─── issue #121: CallableStatement IN binding and OUT registration ─────────────

  behavior of "CallableStatement IN/OUT registration (issue #121)"

  it should "bind IN params and register the OUT param with no overlap, each position exactly once" in {
    val sql  = "{res} = CALL ECHO_FN({s},{n})"
    val ctx  = Interpolator.interpolate(sql)
    val conn = dataSource.getConnection
    try {
      val (stmt, recorder) = RecordingStatementProxy.callable(conn.prepareCall(ctx.queryString))
      stmt.setParams(ctx, Map("s" -> StrParam("abc"), "n" -> LongParam(7L)), Map("res" -> Types.VARCHAR))
      stmt.executeUpdate()

      recorder.maxConcurrentParamCalls shouldBe 1
      recorder.registerCountsByIndex shouldBe Map(ctx.m("res").head -> 1)
      recorder.setterCountsByIndex shouldBe Map(ctx.m("s").head -> 1, ctx.m("n").head -> 1)
      recorder.boundValueAt(ctx.m("s").head) shouldBe "abc"

      stmt.getString(ctx.m("res").head) shouldBe "abc:7"
    } finally conn.close()
  }

  it should "hold the same invariants for a call with only IN parameters" in {
    val sql  = "CALL ECHO_FN({s},{n})"
    val ctx  = Interpolator.interpolate(sql)
    val conn = dataSource.getConnection
    try {
      val (stmt, recorder) = RecordingStatementProxy.callable(conn.prepareCall(ctx.queryString))
      stmt.setParams(ctx, Map("s" -> StrParam("solo"), "n" -> LongParam(1L)), Map.empty)
      stmt.execute()

      recorder.maxConcurrentParamCalls shouldBe 1
      recorder.registerCountsByIndex shouldBe empty
      recorder.setterCountsByIndex shouldBe Map(ctx.m("s").head -> 1, ctx.m("n").head -> 1)
    } finally conn.close()
  }

  it should "hold the same invariants for a call with only an OUT parameter" in {
    val sql  = "{res} = CALL FORTY_TWO()"
    val ctx  = Interpolator.interpolate(sql)
    val conn = dataSource.getConnection
    try {
      val (stmt, recorder) = RecordingStatementProxy.callable(conn.prepareCall(ctx.queryString))
      stmt.setParams(ctx, Map.empty, Map("res" -> Types.BIGINT))
      stmt.executeUpdate()

      recorder.maxConcurrentParamCalls shouldBe 1
      recorder.setterCountsByIndex shouldBe empty
      recorder.registerCountsByIndex shouldBe Map(ctx.m("res").head -> 1)

      stmt.getLong(ctx.m("res").head) shouldBe 42L
    } finally conn.close()
  }

  it should "fail with IllegalArgumentException naming the OUT parameter missing from the SQL placeholders" in {
    val future = client.call(
      "CALL ECHO_FN({s},{n})", // {res} placeholder intentionally absent
      Seq("s"   -> StrParam("x"), "n" -> LongParam(2L)),
      Seq("res" -> Types.VARCHAR),
    )(identity)

    val result = Await.result(future, 10.seconds)
    result shouldBe a[Failure[_]]
    result.failure.exception shouldBe an[IllegalArgumentException]
    result.failure.exception.getMessage should include("res")
  }

  it should "return correct OUT values for every call under concurrent load" in {
    val n       = 50
    val futures = (1 to n).map { i =>
      client.call(
        "{res} = CALL ECHO_FN({s},{n})",
        Seq("s"   -> StrParam(s"in-$i"), "n" -> LongParam(i.toLong)),
        Seq("res" -> Types.VARCHAR),
      ) { result =>
        result.success.value shouldBe Map("res" -> s"in-$i:$i")
      }
    }
    Await.result(Future.sequence(futures), 30.seconds)
    succeed
  }
}
