ThisBuild / organization := "io.cosmospf"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/cosmospf/gatling-jdbc-plugin"),
    "git@github.com:cosmospf/gatling-jdbc-plugin.git",
  ),
)

ThisBuild / description := "Simple gatling JDBC Plugin"
ThisBuild / licenses    := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage    := Some(url("https://github.com/cosmospf/gatling-jdbc-plugin"))
