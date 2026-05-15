package org.galaxio.gatling.javaapi.internal

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.gatling.commons.stats.OK
import io.gatling.commons.validation.{Failure, Success}
import io.gatling.core.session.Session
import org.galaxio.gatling.jdbc.db.{JDBCClient, SQL}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import java.util.{Map => JMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

class UtilsJdbcIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val hikariConfig = new HikariConfig()
  hikariConfig.setJdbcUrl("jdbc:h2:mem:java-utils-integration;DB_CLOSE_DELAY=-1")
  hikariConfig.setUsername("sa")
  hikariConfig.setPassword("")

  private val dataSource  = new HikariDataSource(hikariConfig)
  private val jdbcClient  = JDBCClient(dataSource, Executors.newFixedThreadPool(2))
  private val awaitWindow = 5.seconds

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val connection = dataSource.getConnection
    val statement  = connection.createStatement()
    try {
      statement.execute("CREATE TABLE JAVA_BOOL_TEST (ID INT PRIMARY KEY, NAME VARCHAR(64), FLAG BOOLEAN)")
    } finally {
      statement.close()
      connection.close()
    }
  }

  override protected def afterAll(): Unit = {
    jdbcClient.close()
    super.afterAll()
  }

  private def session(attributes: Map[String, Any] = scala.collection.immutable.Map.empty): Session =
    new Session("utils-jdbc-integration-spec", 1L, attributes, OK, Nil, Session.NothingOnExit, null)

  private def evaluatedParams(values: JMap[String, Object], sessionAttributes: Map[String, Any] = Map.empty): Map[String, Any] =
    Utils
      .getSeq(values)
      .map { case (key, expression) =>
        val value = expression(session(sessionAttributes)) match {
          case Success(v)     => v
          case Failure(error) => fail(error)
        }
        key -> value
      }
      .toMap

  "Java map values" should "insert boolean literals without coercing them to strings" in {
    val cleanupConnection = dataSource.getConnection
    val cleanupStatement  = cleanupConnection.createStatement()
    try {
      cleanupStatement.execute("DELETE FROM JAVA_BOOL_TEST")
    } finally {
      cleanupStatement.close()
      cleanupConnection.close()
    }

    val params = evaluatedParams(
      JMap.of(
        "id",
        Int.box(1),
        "name",
        "#{userName}",
        "flag",
        Boolean.box(true),
      ),
      Map("userName" -> "Alice"),
    )

    val sql     = SQL("INSERT INTO JAVA_BOOL_TEST (ID, NAME, FLAG) VALUES({id}, {name}, {flag})").withParamsMap(params)
    val promise = Promise[Int]()

    jdbcClient.executeUpdate(sql.sql, sql.params)(
      updatedRows => promise.success(updatedRows),
      error => promise.failure(error),
    )

    Await.result(promise.future, awaitWindow) shouldBe 1

    val connection = dataSource.getConnection
    val statement  = connection.createStatement()

    try {
      val resultSet = statement.executeQuery("SELECT NAME, FLAG FROM JAVA_BOOL_TEST WHERE ID = 1")
      resultSet.next() shouldBe true
      resultSet.getString("NAME") shouldBe "Alice"
      resultSet.getBoolean("FLAG") shouldBe true
    } finally {
      statement.close()
      connection.close()
    }
  }
}
