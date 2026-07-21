package org.galaxio.gatling.jdbc.db.testsupport

import com.zaxxer.hikari.HikariDataSource
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.sql.{Connection, DriverManager}
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Shared fixture for db-level specs that run a [[JDBCClient]] against the in-memory H2 pool: owns the data source, blocking
  * pool, and client (torn down in afterAll) and provides the raw-SQL and fresh-connection helpers every such spec repeats.
  * Complements the action-level `JdbcActionSpecFixture` (Gatling scenario context) — this one is for direct client specs.
  *
  * Override [[buildDataSource]] to run the same client against a decorated pool (e.g. [[FailingConnectionDataSource]]).
  */
trait H2ClientSpecFixture extends BeforeAndAfterAll { self: Suite =>

  /** Name of the per-spec in-memory H2 database (kept alive JVM-wide by `DB_CLOSE_DELAY=-1`). */
  protected def dbName: String
  protected def poolSize: Int = 2

  protected def buildDataSource(): HikariDataSource = H2.dataSource(dbName, poolSize)

  protected lazy val dataSource: HikariDataSource  = buildDataSource()
  protected lazy val blockingPool: ExecutorService = Executors.newFixedThreadPool(poolSize)
  protected lazy val client: JDBCClient            = JDBCClient(dataSource, blockingPool)

  override protected def afterAll(): Unit = {
    client.close()
    super.afterAll()
  }

  protected def exec(sql: String): Unit =
    Await.result(client.executeRaw(sql)(identity), 10.seconds)

  /** Runs `body` on a completely independent connection — proves visibility beyond the pool under test. */
  protected def withFreshConnection[T](body: Connection => T): T = {
    val conn = DriverManager.getConnection(H2.jdbcUrl(dbName), "sa", "")
    try body(conn)
    finally conn.close()
  }

  protected def countFromFreshConnection(table: String): Int = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery(s"SELECT COUNT(*) FROM $table")
    rs.next()
    rs.getInt(1)
  }
}
