package org.galaxio.gatling.jdbc.actions

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.galaxio.gatling.jdbc.db.{IntParam, JDBCClient, SqlWithParam, StrParam}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/** Regression tests verifying that JDBCClient invokes the *failure* callback on JDBC errors (issue #27).
  *
  * Every JDBC action (DBQueryAction, DBInsertAction, DBBatchAction, DBCallAction, DBRawQueryAction) routes its JDBC error
  * callback to session.markAsFailed before passing the session to executeNext. These tests confirm that bad SQL or constraint
  * violations cause the failure callback — not the success callback — to fire, which is the precondition for
  * session.markAsFailed to be called in production.
  */
class JDBCClientFailureCallbackSpec extends AnyFlatSpec with Matchers {

  private def withClient[T](f: JDBCClient => T): T = {
    val cfg          = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:jdbc_failure_test;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(4)
    val ds           = new HikariDataSource(cfg)
    val blockingPool = Executors.newFixedThreadPool(4)
    val client       = JDBCClient(ds, blockingPool)
    try f(client)
    finally client.close()
  }

  "JDBCClient.executeRaw" should "invoke the failure callback when SQL is invalid" in
    withClient { client =>
      val latch                            = new CountDownLatch(1)
      var failureCaught: Option[Throwable] = None
      var successCalled                    = false

      client.executeRaw("THIS IS NOT VALID SQL")(
        _ => { successCalled = true; latch.countDown() },
        t => { failureCaught = Some(t); latch.countDown() },
      )

      latch.await(5, TimeUnit.SECONDS) shouldBe true
      successCalled shouldBe false
      failureCaught shouldBe defined
    }

  "JDBCClient.executeSelect" should "invoke the failure callback for a query on a non-existent table" in
    withClient { client =>
      val latch                            = new CountDownLatch(1)
      var failureCaught: Option[Throwable] = None
      var successCalled                    = false

      client.executeSelect("SELECT * FROM no_such_table_xyz WHERE id = {id}", Seq("id" -> IntParam(1)))(
        _ => { successCalled = true; latch.countDown() },
        t => { failureCaught = Some(t); latch.countDown() },
      )

      latch.await(5, TimeUnit.SECONDS) shouldBe true
      successCalled shouldBe false
      failureCaught shouldBe defined
    }

  "JDBCClient.executeUpdate" should "invoke the failure callback when the target table does not exist" in
    withClient { client =>
      val latch                            = new CountDownLatch(1)
      var failureCaught: Option[Throwable] = None
      var successCalled                    = false

      client.executeUpdate("INSERT INTO missing_table (col) VALUES ({col})", Seq("col" -> StrParam("v")))(
        _ => { successCalled = true; latch.countDown() },
        t => { failureCaught = Some(t); latch.countDown() },
      )

      latch.await(5, TimeUnit.SECONDS) shouldBe true
      successCalled shouldBe false
      failureCaught shouldBe defined
    }

  "JDBCClient.batch" should "invoke the failure callback when a batch statement targets a non-existent table" in
    withClient { client =>
      val latch                            = new CountDownLatch(1)
      var failureCaught: Option[Throwable] = None
      var successCalled                    = false

      import org.galaxio.gatling.jdbc.db.SqlWithParam
      val badQuery: SqlWithParam = SqlWithParam("INSERT INTO ghost_table VALUES (1)", Seq.empty)

      client.batch(Seq(badQuery))(
        _ => { successCalled = true; latch.countDown() },
        t => { failureCaught = Some(t); latch.countDown() },
      )

      latch.await(5, TimeUnit.SECONDS) shouldBe true
      successCalled shouldBe false
      failureCaught shouldBe defined
    }

  "JDBCClient.call" should "invoke the failure callback when the stored procedure does not exist" in
    withClient { client =>
      val latch                            = new CountDownLatch(1)
      var failureCaught: Option[Throwable] = None
      var successCalled                    = false

      client.call("CALL no_such_procedure()", Seq.empty, Seq.empty)(
        (_: Map[String, Any]) => { successCalled = true; latch.countDown() },
        t => { failureCaught = Some(t); latch.countDown() },
      )

      latch.await(5, TimeUnit.SECONDS) shouldBe true
      successCalled shouldBe false
      failureCaught shouldBe defined
    }
}
