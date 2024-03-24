package io.cosmospf.load.jdbc.test

import io.gatling.core.Predef._
import io.cosmospf.load.jdbc.Predef._
import io.cosmospf.load.jdbc.test.scenarios.BasicSimulation

class DebugTest extends Simulation {

  setUp(
    BasicSimulation().inject(atOnceUsers(1)),
  ).protocols(dataBase)
    .maxDuration(60)
    .assertions(
      global.failedRequests.percent.is(0.0),
    )

}
