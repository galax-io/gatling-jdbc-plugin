package org.galaxio.gatling.jdbc.actions

import io.gatling.core.Predef._
import org.galaxio.gatling.jdbc.Predef._
import org.galaxio.performance.jdbc.test.dataBase

class JdbcQueryChecksSimulationSpec extends Simulation {

  @volatile private var extractedFirstName: Option[String]            = None
  @volatile private var extractedIds: Option[List[Any]]               = None
  @volatile private var extractedAliasedRow: Option[Map[String, Any]] = None
  @volatile private var extractedAliasedCell: Option[String]          = None

  private val scn = scenario("JDBC query checks")
    .exec(
      jdbc("Create people table").rawSql(
        """CREATE TABLE PEOPLE (
          |  ID INT PRIMARY KEY,
          |  NAME VARCHAR(64)
          |);""".stripMargin,
      ),
    )
    .exec(
      jdbc("Seed people table").rawSql(
        """INSERT INTO PEOPLE (ID, NAME) VALUES
          |(1, 'Alice'),
          |(2, 'Bob');""".stripMargin,
      ),
    )
    .exec(
      jdbc("Select aliased people")
        .query("SELECT ID AS PERSON_ID, NAME AS DISPLAY_NAME FROM PEOPLE ORDER BY ID")
        .check(
          cell("DISPLAY_NAME", 0).find.is("Alice"),
          column("PERSON_ID").find.saveAs("personIds"),
          row(1).find.saveAs("secondRow"),
          cell("DISPLAY_NAME", 1).find.saveAs("secondName"),
        ),
    )
    .exec { session =>
      extractedFirstName = Some(session("secondName").as[String])
      extractedIds = Some(session("personIds").as[List[Any]])
      extractedAliasedRow = Some(session("secondRow").as[Map[String, Any]])
      extractedAliasedCell = Some(session("secondName").as[String])
      session
    }

  after {
    assert(extractedFirstName.contains("Bob"), s"Expected Bob, got $extractedFirstName")
    assert(extractedAliasedCell.contains("Bob"), s"Expected aliased cell Bob, got $extractedAliasedCell")
    assert(extractedIds.contains(List(1, 2)), s"Expected aliased IDs List(1, 2), got $extractedIds")
    assert(
      extractedAliasedRow.contains(Map("PERSON_ID" -> 2, "DISPLAY_NAME" -> "Bob")),
      s"Expected aliased row with PERSON_ID and DISPLAY_NAME, got $extractedAliasedRow",
    )
  }

  setUp(
    scn.inject(atOnceUsers(1)),
  ).protocols(dataBase)
    .maxDuration(30)
    .assertions(
      global.failedRequests.count.is(0L),
      global.successfulRequests.count.is(3L),
    )
}
