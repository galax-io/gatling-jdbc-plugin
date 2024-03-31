package org.galaxio.performance.jdbc.test;

import io.gatling.javaapi.core.Simulation;
import org.galaxio.performance.jdbc.test.scenarios.JdbcBasicSimulation;

import static io.gatling.javaapi.core.OpenInjectionStep.atOnceUsers;

public class JdbcDebugTest extends Simulation {
    {
        setUp(
                JdbcBasicSimulation.scn.injectOpen(atOnceUsers(1))
        ).protocols(JdbcProtocol.dataBase);
    }
}
