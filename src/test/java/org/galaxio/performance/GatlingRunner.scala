package org.galaxio.performance

import io.gatling.app.Gatling
import io.gatling.shared.cli.GatlingCliOptions
import org.galaxio.performance.jdbc.test.JdbcDebugTest

object GatlingRunner {

  def main(args: Array[String]): Unit = {

    // this is where you specify the class you want to run
    val simulationClass = classOf[JdbcDebugTest].getName

    Gatling.main(
      args ++
        Array(
          GatlingCliOptions.Simulation.shortOption,
          simulationClass,
          GatlingCliOptions.ResultsFolder.shortOption,
          "results",
          GatlingCliOptions.Launcher.shortOption,
          "sbt",
        ),
    )
  }

}
