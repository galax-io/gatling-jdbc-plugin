import sbt.*

object Dependencies {

  val gatlingVersion = "3.11.5"

  lazy val gatlingCore: Seq[ModuleID] = Seq(
    "io.gatling" % "gatling-core",
    "io.gatling" % "gatling-core-java",
  ).map(_ % gatlingVersion % "provided")

  lazy val gatling: Seq[ModuleID] = Seq(
    "io.gatling"            % "gatling-test-framework",
    "io.gatling.highcharts" % "gatling-charts-highcharts",
  ).map(_ % gatlingVersion % "it,test")

  lazy val hikari    = "com.zaxxer"     % "HikariCP"  % "7.0.2" exclude ("org.slf4j", "slf4j-api")
  lazy val h2jdbc    = "com.h2database" % "h2"        % "2.4.240" % Test
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.19"  % Test

  lazy val testcontainersScalatest = "com.dimafeng" %% "testcontainers-scala-scalatest"  % "0.44.1" % Test
  lazy val testcontainersPostgres  = "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.44.1" % Test
  lazy val testcontainersMysql     = "com.dimafeng" %% "testcontainers-scala-mysql"      % "0.44.1" % Test

  lazy val postgresql = "org.postgresql" % "postgresql"        % "42.7.7" % Test
  lazy val mysql      = "com.mysql"      % "mysql-connector-j" % "9.3.0"  % Test

}
