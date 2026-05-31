package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.PostgreSQLContainer

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

class PostgreSQLIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val postgres: PostgreSQLContainer[_] = new PostgreSQLContainer("postgres:16-alpine")
  private var dataSource: HikariDataSource     = _
  private var client: JDBCClient               = _
  private val threadPool                       = Executors.newFixedThreadPool(8)

  override def beforeAll(): Unit = {
    postgres.start()

    val cfg = new HikariConfig()
    cfg.setJdbcUrl(postgres.getJdbcUrl)
    cfg.setUsername(postgres.getUsername)
    cfg.setPassword(postgres.getPassword)
    cfg.setMaximumPoolSize(10)
    dataSource = new HikariDataSource(cfg)
    client = JDBCClient(dataSource, threadPool)

    exec[Boolean](client.executeRaw("CREATE TABLE items (id SERIAL PRIMARY KEY, name VARCHAR(100))")(_, _))
      .getOrElse(throw new RuntimeException("Failed to create test table"))
    ()
  }

  override def afterAll(): Unit = {
    client.close()
    postgres.stop()
  }

  private def exec[T](action: (T => Unit, Throwable => Unit) => Unit): Either[Throwable, T] = {
    val latch                        = new CountDownLatch(1)
    var result: Either[Throwable, T] = Left(new RuntimeException("timeout"))
    action(
      v => { result = Right(v); latch.countDown() },
      e => { result = Left(e); latch.countDown() },
    )
    latch.await(10, TimeUnit.SECONDS)
    result
  }

  "JDBCClient" should "execute a raw DDL statement successfully" in {
    exec[Boolean](client.executeRaw("CREATE TABLE IF NOT EXISTS raw_test (id INT)")(_, _)) shouldBe a[Right[_, _]]
  }

  it should "insert a row via executeUpdate" in {
    val affected = exec[Int](
      client.executeUpdate("INSERT INTO items (name) VALUES ({name})", Seq("name" -> StrParam("alice")))(_, _),
    ).getOrElse(fail("executeUpdate failed"))
    affected shouldBe 1
  }

  it should "select inserted rows via executeSelect" in {
    exec[Int](
      client.executeUpdate("INSERT INTO items (name) VALUES ({name})", Seq("name" -> StrParam("bob")))(_, _),
    )
    val rows = exec[List[Map[String, Any]]](
      client.executeSelect("SELECT name FROM items WHERE name = {name}", Seq("name" -> StrParam("bob")))(_, _),
    ).getOrElse(fail("executeSelect failed"))

    rows should not be empty
    rows.head("name") shouldBe "bob"
  }

  it should "execute batch inserts and return row counts" in {
    val queries = Seq(
      SQL("INSERT INTO items (name) VALUES ({n})").withParams("n" -> StrParam("batch1")),
      SQL("INSERT INTO items (name) VALUES ({n})").withParams("n" -> StrParam("batch2")),
      SQL("INSERT INTO items (name) VALUES ({n})").withParams("n" -> StrParam("batch3")),
    )
    val counts  = exec[Array[Int]](client.batch(queries)(_, _)).getOrElse(fail("batch failed"))
    counts should have length 3
    counts.foreach(_ shouldBe 1)
  }

  it should "propagate SQLException for invalid SQL" in {
    val result = exec[Boolean](client.executeRaw("THIS IS NOT VALID SQL !!!")(_, _))
    result shouldBe a[Left[_, _]]
  }

  it should "propagate exception when connecting to an unavailable host" in {
    val badCfg    = new HikariConfig()
    badCfg.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/nonexistent")
    badCfg.setUsername("u")
    badCfg.setPassword("p")
    badCfg.setConnectionTimeout(1000)
    badCfg.setMaximumPoolSize(1)
    // Disable fail-fast so the constructor doesn't throw — error surfaces via the async callback instead
    badCfg.setInitializationFailTimeout(-1)
    val badSource = new HikariDataSource(badCfg)
    val badClient = JDBCClient(badSource, Executors.newFixedThreadPool(1))
    try {
      exec[Boolean](badClient.executeRaw("SELECT 1")(_, _)) shouldBe a[Left[_, _]]
    } finally {
      badClient.close()
    }
  }

  it should "complete a slow query and return a result" in {
    val start   = System.currentTimeMillis()
    val result  = exec[Boolean](client.executeRaw("SELECT pg_sleep(0.5)")(_, _))
    val elapsed = System.currentTimeMillis() - start
    result shouldBe a[Right[_, _]]
    elapsed should be >= 500L
  }

  it should "propagate exception when server statement_timeout fires" in {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl(postgres.getJdbcUrl)
    cfg.setUsername(postgres.getUsername)
    cfg.setPassword(postgres.getPassword)
    cfg.setMaximumPoolSize(2)
    cfg.setConnectionInitSql("SET statement_timeout = '200ms'")
    val ds  = new HikariDataSource(cfg)
    val c   = JDBCClient(ds, Executors.newFixedThreadPool(2))
    try {
      val result = exec[Boolean](c.executeRaw("SELECT pg_sleep(1)")(_, _))
      result shouldBe a[Left[_, _]]
    } finally {
      c.close()
    }
  }

  it should "run concurrent queries without failures" in {
    val n          = 20
    val latch      = new CountDownLatch(n)
    val failures   = new AtomicInteger(0)
    // Dedicated pool sized to n so all queries dispatch without queuing
    val concPool   = Executors.newFixedThreadPool(n)
    val concClient = JDBCClient(dataSource, concPool)
    try {
      (1 to n).foreach { i =>
        concClient.executeRaw(s"SELECT $i")(
          _ => latch.countDown(),
          _ => { failures.incrementAndGet(); latch.countDown() },
        )
      }
      latch.await(30, TimeUnit.SECONDS) shouldBe true
      failures.get() shouldBe 0
    } finally {
      concPool.shutdown()
    }
  }

  it should "return connections to pool after successful query" in {
    exec[Boolean](client.executeRaw("SELECT 1")(_, _))
    Thread.sleep(200)
    dataSource.getHikariPoolMXBean.getActiveConnections shouldBe 0
  }

  it should "return connections to pool after failed query" in {
    exec[Boolean](client.executeRaw("INVALID SQL")(_, _))
    Thread.sleep(200)
    dataSource.getHikariPoolMXBean.getActiveConnections shouldBe 0
  }
}
