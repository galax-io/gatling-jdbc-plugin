package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.PostgreSQLContainer

import java.util.concurrent.Executors
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Failure

class PostgreSQLIntegrationSpec extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {

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

    Await.result(
      client.executeRaw("CREATE TABLE items (id SERIAL PRIMARY KEY, name VARCHAR(100))") { result =>
        result.success.value shouldBe false
      },
      10.seconds,
    )
  }

  override def afterAll(): Unit = {
    client.close()
    postgres.stop()
  }

  "JDBCClient" should "execute a raw DDL statement successfully" in {
    client.executeRaw("CREATE TABLE IF NOT EXISTS raw_test (id INT)") { result =>
      result.success.value shouldBe false
    }
  }

  it should "insert a row via executeUpdate" in {
    client.executeUpdate("INSERT INTO items (name) VALUES ({name})", Seq("name" -> StrParam("alice"))) { result =>
      result.success.value shouldBe 1
    }
  }

  it should "select inserted rows via executeSelect" in {
    client
      .executeUpdate("INSERT INTO items (name) VALUES ({name})", Seq("name" -> StrParam("bob"))) { result =>
        result.success.value shouldBe 1
      }
      .flatMap(_ =>
        client.executeSelect("SELECT name FROM items WHERE name = {name}", Seq("name" -> StrParam("bob"))) { result =>
          val rows = result.success.value

          rows should not be empty
          rows.head("name") shouldBe "bob"
        },
      )
  }

  it should "execute batch inserts and return row counts" in {
    val queries = Seq(
      SQL("INSERT INTO items (name) VALUES ({n})").withParams("n" -> StrParam("batch1")),
      SQL("INSERT INTO items (name) VALUES ({n})").withParams("n" -> StrParam("batch2")),
      SQL("INSERT INTO items (name) VALUES ({n})").withParams("n" -> StrParam("batch3")),
    )

    client.batch(queries) { result =>
      result.success.value shouldBe Array(1, 1, 1)
    }
  }

  it should "propagate SQLException for invalid SQL" in {
    client.executeRaw("THIS IS NOT VALID SQL !!!") { result =>
      result shouldBe a[Failure[_]]
    }
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

    badClient
      .executeRaw("SELECT 1") { result =>
        result shouldBe a[Failure[_]]
      }
      .andThen(_ => badClient.close())
  }

  it should "complete a slow query and return a result" in {
    val start = System.currentTimeMillis()

    client.executeRaw("SELECT pg_sleep(0.5)") { result =>
      val elapsed = System.currentTimeMillis() - start

      result.success.value shouldBe true
      elapsed should be >= 500L
    }
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

    c.executeRaw("SELECT pg_sleep(1)") { result =>
      result shouldBe a[Failure[_]]
    }.andThen(_ => c.close())
  }

  it should "run concurrent queries without failures" in {
    val n          = 20
    val concPool   = Executors.newFixedThreadPool(n)
    val concClient = JDBCClient(dataSource, concPool)

    Future.sequence {
      (1 to n).map { i =>
        concClient.executeRaw(s"SELECT $i") { result =>
          result.success.value shouldBe true
        }
      }
    }.map { results =>
      results should have length n
    }.andThen { case _ =>
      concPool.shutdown()
    }
  }

  it should "return connections to pool after successful query" in {
    client
      .executeRaw("SELECT 1") { result =>
        result.success.value shouldBe true
      }
      .map { _ =>
        Thread.sleep(200)
        dataSource.getHikariPoolMXBean.getActiveConnections shouldBe 0
      }
  }

  it should "return connections to pool after failed query" in {
    client
      .executeRaw("INVALID SQL") { result =>
        result shouldBe a[Failure[_]]
      }
      .map { _ =>
        Thread.sleep(200)
        dataSource.getHikariPoolMXBean.getActiveConnections shouldBe 0
      }
  }

  // Regression for issue #122 (US2): result keys are the labels as written in the query.
  // PostgreSQL lower-cases unquoted labels; quoted labels stay verbatim.
  it should "key aliased columns by the alias with PostgreSQL case rules" in {
    client
      .executeUpdate("INSERT INTO items (name) VALUES ({name})", Seq("name" -> StrParam("label_case"))) { result =>
        result.success.value shouldBe 1
      }
      .flatMap { _ =>
        client.executeSelect(
          """SELECT name AS customer_name, name AS "MixedCase" FROM items WHERE name = {name}""",
          Seq("name" -> StrParam("label_case")),
        ) { result =>
          val rows = result.success.value

          rows should have size 1
          rows.head.keySet shouldBe Set("customer_name", "MixedCase")
          rows.head("customer_name") shouldBe "label_case"
          rows.head("MixedCase") shouldBe "label_case"
        }
      }
  }

  // Regression for issue #86 (US4): the discard path's streaming mechanism exists FOR PostgreSQL —
  // a plugin-managed read transaction + forward-only cursor + fetch size is the only way pgjdbc streams.
  // Prove the drain completes, counts correctly, and the transaction scope commits cleanly on a real PG.
  it should "drain a large result via the discard path without retaining rows" in {
    client.executeSelectDiscard("SELECT gs FROM generate_series(1, 100000) AS gs", Seq.empty, None) { result =>
      result.success.value shouldBe 100000L
    }
  }

  it should "enforce the maxRows cap on the discard path" in {
    client.executeSelectDiscard("SELECT gs FROM generate_series(1, 11) AS gs", Seq.empty, Some(10)) { result =>
      result.failure.exception.getMessage should include("maxRows")
    }
  }

  // Regression for issue #120: under concurrent load every multi-param insert must write
  // exactly the values declared for it — no swapped or corrupted bindings between users.
  it should "bind every parameter of concurrent multi-param inserts exactly to its declared value" in {
    val n = 50

    client
      .executeRaw(
        """CREATE TABLE IF NOT EXISTS conc_items (
          |  id   INT PRIMARY KEY,
          |  name VARCHAR(100),
          |  flag BOOLEAN,
          |  val  DOUBLE PRECISION
          |)""".stripMargin,
      ) { result =>
        result.success.value shouldBe false
      }
      .flatMap { _ =>
        Future.sequence {
          (1 to n).map { i =>
            client.executeUpdate(
              "INSERT INTO conc_items (id, name, flag, val) VALUES ({id},{name},{flag},{val})",
              Seq(
                "id"   -> IntParam(i),
                "name" -> StrParam(s"row-$i"),
                "flag" -> BooleanParam(i % 2 == 0),
                "val"  -> DoubleParam(i * 1.5),
              ),
            ) { result =>
              result.success.value shouldBe 1
            }
          }
        }
      }
      .flatMap { _ =>
        client.executeSelect("SELECT id, name, flag, val FROM conc_items", Seq.empty) { result =>
          val rows = result.success.value
          rows should have size n.toLong

          rows.foreach { row =>
            val id = row("id").asInstanceOf[Number].intValue()
            row("name") shouldBe s"row-$id"
            row("flag") shouldBe (id % 2 == 0)
            row("val") shouldBe id * 1.5
          }
          succeed
        }
      }
  }
}
