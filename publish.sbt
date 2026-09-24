ThisBuild / versionScheme        := Some("semver-spec")
ThisBuild / organization         := "org.galaxio"
ThisBuild / organizationName     := "Galaxio Team"
ThisBuild / organizationHomepage := Some(uri("https://github.com/galax-io"))
ThisBuild / description          := "Gatling plugin that adds JDBC support for load testing: provides a DSL and helpers to execute SQL via pooled connections (HikariCP), assert results, and measure database latency/throughput."

ThisBuild / homepage := Some(uri("https://github.com/galax-io/gatling-jdbc-plugin"))
ThisBuild / scmInfo  := Some(
  ScmInfo(
    uri("https://github.com/galax-io/gatling-jdbc-plugin"),
    "git@github.com:galax-io/gatling-jdbc-plugin.git",
  ),
)

ThisBuild / scalaVersion := "2.13.18"

ThisBuild / developers := List(
  Developer(
    id = "jigarkhwar",
    name = "Ioann Akhaltsev",
    email = "jigarkhwar88@gmail.com",
    url = uri("https://github.com/jigarkhwar"),
  ),
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / licenses             := Seq(License("Apache-2.0", uri("https://www.apache.org/licenses/LICENSE-2.0")))
