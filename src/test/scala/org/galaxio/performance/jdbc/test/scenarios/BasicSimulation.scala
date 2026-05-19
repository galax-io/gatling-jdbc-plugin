package org.galaxio.performance.jdbc.test.scenarios

import io.gatling.core.Predef._
import io.gatling.core.feeder.Feeder
import io.gatling.core.structure.ScenarioBuilder
import org.galaxio.performance.jdbc.test.cases.Actions

import java.util.concurrent.atomic.AtomicLong

object BasicSimulation {
  def apply(): ScenarioBuilder = new BasicSimulation().scn

}

class BasicSimulation {
  val c               = new AtomicLong(1)
  val f: Feeder[Long] = Iterator.continually(c.getAndIncrement()).map(i => Map("i" -> i))

  val scn: ScenarioBuilder = scenario("Basic")
    .exec(Actions.createTable())
    .exec(Actions.createProcedure())
    .doWhile(_("i").as[Long] < 2)(
      feed(f)
        .exec(Actions.insertTest())
        .exec(Actions.callTest())
        .exec(Actions.selectTest),
    )
    .exec(Actions.batchTest)
    .exec(Actions.selectTT)
    .exec(Actions.selectAfterBatch)
    .exec { s =>
      println(s("ttr").as[List[Map[String, Any]]])
      println(s("R").as[List[Map[String, Any]]])
      println(s("RR").as[List[Map[String, Any]]])
      s
    }
    .exec(Actions.checkBatchTestTable)
    .exec(Actions.checkBatchTT)

}
