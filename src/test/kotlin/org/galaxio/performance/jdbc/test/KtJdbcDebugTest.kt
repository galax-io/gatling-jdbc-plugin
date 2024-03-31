package org.galaxio.gatling.jdbc.test

import io.gatling.javaapi.core.OpenInjectionStep.atOnceUsers
import io.gatling.javaapi.core.Simulation
import org.galaxio.gatling.jdbc.test.scenarios.KtJdbcBasicSimulation.scn

class KtJdbcDebugTest : Simulation() {
    init {
        setUp(
            scn.injectOpen(atOnceUsers(1))
        ).protocols(KtJdbcProtocol.dataBase)
    }
}