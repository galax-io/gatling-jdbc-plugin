package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariConfig
import org.galaxio.gatling.jdbc.db.testsupport.CloseCountingDataSource
import org.scalatest.TryValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Try}

/** Acceptance tests for issue #100: resources must be released exactly once when an operation fails synchronously.
  *
  * The pre-#59 ResourceFut installed its cleanup only after the use-function returned a Future, so a synchronous throw (e.g. a
  * missing parameter binding) skipped release entirely. #59 replaced it with Using.Manager; these tests pin the exactly-once
  * release per resource with close-counting proxies, the error-preservation semantics narrowed after adversarial review (a
  * non-fatal release failure is suppressed under the original exception, never masking it), and leak-free behavior under a soak
  * loop.
  */
class ResourceReleaseOnSyncThrowSpec extends AnyFlatSpec with Matchers {

  /** SQL whose {b} placeholder is intentionally left unbound: setParams throws synchronously inside the Using block, before any
    * statement execution.
    */
  private val unboundSql = "SELECT id FROM rel_items WHERE id = {a} AND name = {b}"

  private def withCountingClient[T](f: (CloseCountingDataSource, JDBCClient) => T): T = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:release_test;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(2)
    val ds  = new CloseCountingDataSource(cfg)

    val client = JDBCClient(ds, Executors.newFixedThreadPool(2))
    try {
      // DB_CLOSE_DELAY=-1 keeps the named in-memory DB alive across suite runs in one JVM —
      // clear leftovers so repeated testOnly runs don't hit PK violations.
      Await.result(
        client.executeRaw(
          "CREATE TABLE IF NOT EXISTS rel_items (id INT PRIMARY KEY, name VARCHAR(64)); DELETE FROM rel_items",
        )(identity),
        10.seconds,
      )
      ds.resetTracking()
      f(ds, client)
    } finally client.close()
  }

  private def failingSelect(client: JDBCClient): Try[List[Map[String, Any]]] =
    Await.result(client.executeSelect(unboundSql, Seq("a" -> IntParam(1)))(identity), 10.seconds)

  "a synchronously failing operation" should "surface the original exception and release every resource exactly once" in
    withCountingClient { (ds, client) =>
      val result = failingSelect(client)

      result shouldBe a[Failure[_]]
      result.failure.exception shouldBe a[NoSuchElementException]

      ds.trackedResources should not be empty
      ds.trackedResources.map(_.kind) should contain allOf ("connection", "statement")
      all(ds.trackedResources.map(_.closeCount.get())) shouldBe 1

      Thread.sleep(200)
      ds.getHikariPoolMXBean.getActiveConnections shouldBe 0
    }

  it should "keep the original exception primary when release itself fails, attaching the close failure as suppressed" in
    withCountingClient { (ds, client) =>
      ds.failNextStatementClose = true

      val result = failingSelect(client)

      result shouldBe a[Failure[_]]
      val exception = result.failure.exception
      exception shouldBe a[NoSuchElementException]
      exception.getSuppressed.map(_.getMessage) should contain("injected close failure")

      all(ds.trackedResources.map(_.closeCount.get())) shouldBe 1

      Thread.sleep(200)
      ds.getHikariPoolMXBean.getActiveConnections shouldBe 0
    }

  it should "not leak any resource across a soak loop of failing operations" in
    withCountingClient { (ds, client) =>
      val iterations = 100

      (1 to iterations).foreach { _ =>
        failingSelect(client) shouldBe a[Failure[_]]
      }

      // one connection + one statement acquired (and released exactly once) per iteration
      ds.trackedResources should have size (iterations * 2).toLong
      all(ds.trackedResources.map(_.closeCount.get())) shouldBe 1

      Thread.sleep(200)
      ds.getHikariPoolMXBean.getActiveConnections shouldBe 0
    }

  "a successful operation" should "also release connection, statement and result set exactly once" in
    withCountingClient { (ds, client) =>
      Await
        .result(
          client.executeUpdate(
            "INSERT INTO rel_items (id, name) VALUES ({id},{name})",
            Seq("id" -> IntParam(1), "name" -> StrParam("ok")),
          )(identity),
          10.seconds,
        )
        .success
        .value shouldBe 1

      val selected = Await.result(
        client.executeSelect("SELECT id, name FROM rel_items WHERE id = {id}", Seq("id" -> IntParam(1)))(identity),
        10.seconds,
      )
      selected.success.value should have size 1

      ds.trackedResources.map(_.kind).sorted shouldBe List("connection", "connection", "resultset", "statement", "statement")
      all(ds.trackedResources.map(_.closeCount.get())) shouldBe 1

      Thread.sleep(200)
      ds.getHikariPoolMXBean.getActiveConnections shouldBe 0
    }
}
