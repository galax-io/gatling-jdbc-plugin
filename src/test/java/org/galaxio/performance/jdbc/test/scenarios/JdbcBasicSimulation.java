package org.galaxio.performance.jdbc.test.scenarios;

import io.gatling.javaapi.core.ScenarioBuilder;
import org.galaxio.performance.jdbc.test.cases.JdbcActions;

import static io.gatling.javaapi.core.CoreDsl.scenario;


public class JdbcBasicSimulation {
    public static ScenarioBuilder scn = scenario("JDBC scenario")
            .exec(JdbcActions.createBadTable())
            .exec(session -> {
                boolean jdbcIsFailed =  session.getBoolean("jdbcFailed");
                boolean sessionIsFailed = session.isFailed();
                String jdbcErrorMessage = session.getString("jdbcErrorMessage");
                return session;
            })
            .exec(JdbcActions.createTable())
            .exec(session -> {
                boolean jdbcIsFailed =  session.getBoolean("jdbcFailed");
                boolean sessionIsFailed = session.isFailed();
                boolean hasJdbcErrorMessage = session.contains("jdbcErrorMessage");
                return session;
            })
            .exec(JdbcActions.createprocedure())
            .exec(JdbcActions.insertTest())
            .exec( session -> {
                boolean jdbcIsFailed =  session.getBoolean("jdbcFailed");
                boolean sessionIsFailed = session.isFailed();
                boolean hasJdbcErrorMessage = session.contains("jdbcErrorMessage");
                return session;
            })
            .exec(JdbcActions.callTest())
            .exec(JdbcActions.batchTest())
            .exec(JdbcActions.selectTT())
            .exec(JdbcActions.selectTest())
            .exec(JdbcActions.selectAfterBatch())
            .exec(JdbcActions.checkTestTableAfterBatch())
            ;
}
