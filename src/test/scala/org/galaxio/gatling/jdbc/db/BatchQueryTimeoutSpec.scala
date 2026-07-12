package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.PostgreSQLContainer

import java.sql.SQLException
import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Failure

/** Acceptance tests for issue #83: batch statements must honor the protocol-level queryTimeout.
  *
  * The pre-#59 implementation prepared batch statements without applying queryTimeoutSeconds, so a slow batch could wait
  * indefinitely. These tests prove on real PostgreSQL that a configured timeout aborts a slow batch as a Failure (KO) within
  * the timeout plus a fixed margin, that fast batches under the same client are unaffected, and that batches without a
  * configured timeout gain no implicit one.
  */
class BatchQueryTimeoutSpec extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {

  private val postgres: PostgreSQLContainer[_] = new PostgreSQLContainer("postgres:16-alpine")

  /** Deadline for the aborted slow batch: 1s configured timeout + driver cancel margin — far below the 10s sleep. */
  private val abortDeadlineMs = 5000L

  override def beforeAll(): Unit = {
    postgres.start()

    val (_, client) = mkClient(None)
    Await.result(
      client.executeRaw(
        "CREATE TABLE slow_batch (txt TEXT); CREATE TABLE fast_batch (id INT PRIMARY KEY, name VARCHAR(100))",
      ) { result =>
        result.success.value shouldBe false
      },
      30.seconds,
    )
    client.close()
  }

  override def afterAll(): Unit =
    postgres.stop()

  private def mkClient(timeout: Option[FiniteDuration]): (HikariDataSource, JDBCClient) = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl(postgres.getJdbcUrl)
    cfg.setUsername(postgres.getUsername)
    cfg.setPassword(postgres.getPassword)
    cfg.setMaximumPoolSize(2)
    val ds  = new HikariDataSource(cfg)
    (ds, JDBCClient(ds, Executors.newFixedThreadPool(2), timeout))
  }

  "batch with configured queryTimeout" should "abort a slow batch as a Failure within the timeout plus a margin" in {
    val (ds, client) = mkClient(Some(1.second))
    val start        = System.currentTimeMillis()

    client
      .batch(Seq(SqlWithParam("INSERT INTO slow_batch (txt) SELECT 'x' FROM pg_sleep(10)", Seq.empty))) { result =>
        val elapsed = System.currentTimeMillis() - start

        result shouldBe a[Failure[_]]
        result.failure.exception shouldBe a[SQLException]
        elapsed should be < abortDeadlineMs
      }
      .flatMap { _ =>
        // transaction rolled back and nothing committed
        client.executeSelect("SELECT COUNT(*) AS cnt FROM slow_batch", Seq.empty) { result =>
          result.success.value.head("cnt").asInstanceOf[Number].longValue() shouldBe 0L
        }
      }
      .map { assertion =>
        Thread.sleep(200)
        ds.getHikariPoolMXBean.getActiveConnections shouldBe 0
        assertion
      }
      .andThen(_ => client.close())
  }

  it should "leave fast batches unaffected under the same client" in {
    val (_, client) = mkClient(Some(1.second))
    val queries     = Seq(
      SQL("INSERT INTO fast_batch (id, name) VALUES ({id},{name})").withParams("id" -> IntParam(1), "name" -> StrParam("a")),
      SQL("INSERT INTO fast_batch (id, name) VALUES ({id},{name})").withParams("id" -> IntParam(2), "name" -> StrParam("b")),
    )

    client
      .batch(queries) { result =>
        result.success.value shouldBe Array(1, 1)
      }
      .andThen(_ => client.close())
  }

  "batch without queryTimeout" should "complete a slow batch without any implicit timeout" in {
    val (_, client) = mkClient(None)
    val start       = System.currentTimeMillis()

    client
      .batch(Seq(SqlWithParam("INSERT INTO slow_batch (txt) SELECT 'y' FROM pg_sleep(2)", Seq.empty))) { result =>
        val elapsed = System.currentTimeMillis() - start

        result.success.value shouldBe Array(1)
        elapsed should be >= 2000L
      }
      .andThen(_ => client.close())
  }
}
