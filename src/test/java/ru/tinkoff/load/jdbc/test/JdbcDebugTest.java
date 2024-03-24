package io.cosmospf.load.jdbc.test;

import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.OpenInjectionStep.atOnceUsers;
import static io.cosmospf.load.jdbc.test.JdbcProtocol.dataBase;
import static io.cosmospf.load.jdbc.test.scenarios.JdbcBasicSimulation.scn;

public class JdbcDebugTest extends Simulation {
    {
        setUp(
                scn.injectOpen(atOnceUsers(1))
        ).protocols(dataBase);
    }
}
