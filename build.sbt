import Dependencies.*

lazy val root = (project in file("."))
  .enablePlugins(GitVersioning, GatlingPlugin)
  .settings(
    name                        := "gatling-jdbc-plugin",
    scalaVersion                := "2.13.18",
    // Do not publish artifacts for Gatling-specific configurations (simulations/tests)
    Gatling / publishArtifact   := false,
    GatlingIt / publishArtifact := false,
    libraryDependencies ++= gatling ++ gatlingCore,
    libraryDependencies ++= Seq(hikari, h2jdbc, scalatest),
    libraryDependencies ++= Seq(
      testcontainersScalatest,
      testcontainersPostgres,
      testcontainersMysql,
      postgresql,
      mysql,
    ),
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "org.galaxio.gatling.jdbc.tags.DockerTest"),
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",            // Option and arguments on same line
      "-Xfatal-warnings", // New lines for each options
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-language:higherKinds",
      "-language:existentials",
      "-language:postfixOps",
    ),
  )
