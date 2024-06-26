package org.galaxio.performance.jdbc.test

import io.gatling.app.Gatling
import io.gatling.core.config.GatlingPropertiesBuilder
import org.galaxio.performance.jdbc.test.KtJdbcDebugTest;

object GatlingRunner {

  def main(args: Array[String]): Unit = {

    // this is where you specify the class you want to run
    val simulationClass = classOf[KtJdbcDebugTest].getName

    val props = new GatlingPropertiesBuilder
    props.simulationClass(simulationClass)

    Gatling.fromMap(props.build)
  }

}
