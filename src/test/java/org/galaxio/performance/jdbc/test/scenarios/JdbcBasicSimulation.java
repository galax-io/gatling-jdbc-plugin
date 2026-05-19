package org.galaxio.performance.jdbc.test.scenarios;

import io.gatling.javaapi.core.ScenarioBuilder;
import org.galaxio.performance.jdbc.test.cases.JdbcActions;

import static io.gatling.javaapi.core.CoreDsl.scenario;

public class JdbcBasicSimulation {
    public static ScenarioBuilder scn = scenario("JDBC scenario")
            .exec(JdbcActions.createTable())
            .exec(JdbcActions.createprocedure())
            .exec(JdbcActions.insertTest())
            .exec(JdbcActions.callTest())
            .exec(JdbcActions.batchTest())
            .exec(JdbcActions.selectTT())
            .exec(JdbcActions.selectTest())
            .exec(JdbcActions.selectAfterBatch())
            .exec(JdbcActions.checkTestTableAfterBatch())
            ;
}
