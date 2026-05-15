package org.galaxio.gatling.jdbc.db

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager

class ResultSetOpsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val connection = DriverManager.getConnection("jdbc:h2:mem:result-set-ops;DB_CLOSE_DELAY=-1", "sa", "")

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val statement = connection.createStatement()
    try {
      statement.execute("CREATE TABLE PEOPLE (ID INT PRIMARY KEY, NAME VARCHAR(64))")
      statement.execute("INSERT INTO PEOPLE (ID, NAME) VALUES (1, 'Alice')")
    } finally {
      statement.close()
    }
  }

  override protected def afterAll(): Unit = {
    connection.close()
    super.afterAll()
  }

  "ResultSetOps" should "use column labels so aliases are available to checks" in {
    val statement = connection.createStatement()
    try {
      val rs = statement.executeQuery("SELECT ID AS PERSON_ID, NAME AS DISPLAY_NAME FROM PEOPLE")
      val rows = rs.iterator.toList

      rows should have size 1
      rows.head shouldBe Map("PERSON_ID" -> 1, "DISPLAY_NAME" -> "Alice")
    } finally {
      statement.close()
    }
  }
}
