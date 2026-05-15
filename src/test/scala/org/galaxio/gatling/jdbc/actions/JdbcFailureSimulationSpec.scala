package org.galaxio.gatling.jdbc.actions

import io.gatling.core.Predef._
import org.galaxio.gatling.jdbc.Predef._
import org.galaxio.performance.jdbc.test.dataBase

class JdbcFailureSimulationSpec extends Simulation {

  @volatile private var sessionFailedAfterBadQuery: Option[Boolean] = None
  @volatile private var sessionOkAfterGoodQuery: Option[Boolean]    = None

  private val scn = scenario("JDBC failure propagation")
    .exec(
      jdbc("Create test table")
        .rawSql("CREATE TABLE IF NOT EXISTS FAIL_TEST (ID INT PRIMARY KEY, NAME VARCHAR(64))"),
    )
    .exec { session =>
      sessionOkAfterGoodQuery = Some(session.isFailed)
      session
    }
    .exec(
      jdbc("Bad SQL query")
        .rawSql("SELECT * FROM NONEXISTENT_TABLE_12345"),
    )
    .exec { session =>
      sessionFailedAfterBadQuery = Some(session.isFailed)
      session
    }

  after {
    assert(
      sessionOkAfterGoodQuery.contains(false),
      s"Session should NOT be failed after successful query, got: $sessionOkAfterGoodQuery",
    )
    assert(
      sessionFailedAfterBadQuery.contains(true),
      s"Session SHOULD be failed after bad query, got: $sessionFailedAfterBadQuery",
    )
  }

  setUp(
    scn.inject(atOnceUsers(1)),
  ).protocols(dataBase)
    .maxDuration(30)
    .assertions(
      global.failedRequests.count.is(1L),
      global.successfulRequests.count.gte(1L),
    )
}
