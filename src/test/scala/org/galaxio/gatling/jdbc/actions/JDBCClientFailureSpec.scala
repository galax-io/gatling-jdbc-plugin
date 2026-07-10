package org.galaxio.gatling.jdbc.actions

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.galaxio.gatling.jdbc.db.{IntParam, JDBCClient, SqlWithParam, StrParam}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest._
import org.h2.jdbc.JdbcSQLSyntaxErrorException

import java.util.concurrent.Executors
import org.scalatest.TryValues._

import scala.concurrent.Future

/** Regression tests verifying that JDBCClient fails the returned Try on JDBC errors (issue #27).
  *
  * Every JDBC action (DBQueryAction, DBInsertAction, DBBatchAction, DBCallAction, DBRawQueryAction) routes its JDBC error
  * callback to session.markAsFailed before passing the session to executeNext. These tests confirm that bad SQL or constraint
  * violations causes the returned Try to be a Failure, which is the precondition for session.markAsFailed to be called in
  * production.
  */
class JDBCClientFailureSpec extends AsyncFlatSpec with Matchers {

  private def withClient[T](f: JDBCClient => Future[T]): Future[T] = {
    val cfg          = new HikariConfig()
    cfg.setJdbcUrl("jdbc:h2:mem:jdbc_failure_test;DB_CLOSE_DELAY=-1")
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(4)
    val ds           = new HikariDataSource(cfg)
    val blockingPool = Executors.newFixedThreadPool(4)
    val client       = JDBCClient(ds, blockingPool)

    f(client)
      .andThen(_ => client.close())
  }

  "JDBCClient.executeRaw" should "fail the Try if the SQL is invalid" in
    withClient(_.executeRaw("THIS IS NOT VALID SQL") { result =>
      result.failure.exception shouldBe a[JdbcSQLSyntaxErrorException]
    })

  "JDBCClient.executeSelect" should "fail the Try for a query on a non-existent table" in
    withClient(_.executeSelect("SELECT * FROM no_such_table_xyz WHERE id = {id}", Seq("id" -> IntParam(1))) { result =>
      result.failure.exception shouldBe a[JdbcSQLSyntaxErrorException]
    })

  "JDBCClient.executeUpdate" should "fail the Try when the target table does not exist" in
    withClient(_.executeUpdate("INSERT INTO missing_table (col) VALUES ({col})", Seq("col" -> StrParam("v"))) { result =>
      result.failure.exception shouldBe a[JdbcSQLSyntaxErrorException]
    })

  "JDBCClient.batch" should "fail the Try when a batch statement targets a non-existent table" in {
    val badQuery = SqlWithParam("INSERT INTO ghost_table VALUES (1)", Seq.empty)

    withClient(_.batch(Seq(badQuery)) { result =>
      result.failure.exception shouldBe a[JdbcSQLSyntaxErrorException]
    })
  }

  "JDBCClient.call" should "fail the Try when the stored procedure does not exist" in
    withClient(_.call("CALL no_such_procedure()", Seq.empty, Seq.empty) { result =>
      result.failure.exception shouldBe a[JdbcSQLSyntaxErrorException]
    })
}
