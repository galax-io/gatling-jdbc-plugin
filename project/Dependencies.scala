import sbt.*

object Dependencies {

  val gatlingVersion = "3.13.5"

  lazy val gatlingCore: Seq[ModuleID] = Seq(
    "io.gatling" % "gatling-core",
    "io.gatling" % "gatling-core-java",
  ).map(_ % gatlingVersion % "provided")

  lazy val gatling: Seq[ModuleID] = Seq(
    "io.gatling"            % "gatling-test-framework",
    "io.gatling.highcharts" % "gatling-charts-highcharts",
  ).map(_ % gatlingVersion % "it,test")

  lazy val hikari    = "com.zaxxer"     % "HikariCP"  % "6.3.3" exclude ("org.slf4j", "slf4j-api")
  lazy val h2jdbc    = "com.h2database" % "h2"        % "2.4.240" % Test
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.20"  % Test

  private val testcontainersVersion = "1.21.0"
  lazy val testcontainersPg         = "org.testcontainers" % "postgresql" % testcontainersVersion % Test
  lazy val postgresJdbc             = "org.postgresql"     % "postgresql" % "42.7.13"             % Test

}
