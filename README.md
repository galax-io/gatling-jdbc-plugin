# Gatling JDBC Plugin
[![CI](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml) [![Maven Central](https://img.shields.io/maven-central/v/org.galaxio/gatling-jdbc-plugin_2.13.svg?color=success)](https://search.maven.org/search?q=org.galaxio.gatling-jdbc-plugin) [![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org) [![codecov.io](https://codecov.io/github/galax-io/gatling-jdbc-plugin/coverage.svg?branch=master)](https://codecov.io/github/galax-io/gatling-jdbc-plugin?branch=master)

Plugin for support performance testing with JDBC in Gatling(3.9.x)

# Usage

## Getting Started
Plugin is currently available for Scala 2.13.

You may add plugin as dependency in project with your tests. Write this to your build.sbt:

``` scala
libraryDependencies += "org.galaxio" %% "gatling-jdbc-plugin" % <version> % Test
``` 

## Using Gatling Session Variables

The plugin supports [Gatling Expression Language (EL)](https://docs.gatling.io/reference/script/core/session/el/) in SQL queries.
Use `#{variableName}` to reference values stored in the Gatling session.

### `query()` with EL

Scala:
``` scala
exec(session => session.set("tableName", "USERS"))
.exec(jdbc("dynamic query").query("SELECT * FROM #{tableName} WHERE id = #{id}"))
```

Java:
``` java
.exec(session -> session.set("tableName", "USERS"))
.exec(jdbc("dynamic query").query("SELECT * FROM #{tableName} WHERE id = #{id}"))
```

### `queryP()` with EL

`queryP` uses two different syntaxes:
- `{param}` in SQL string — prepared statement placeholder (replaced with `?`)
- `"#{var}"` in `.params()` — Gatling EL, resolves value from session at runtime

Scala:
``` scala
exec(
  jdbc("parameterized query")
    .queryP("SELECT * FROM TEST_TABLE WHERE id = {id}")
    .params("id" -> "#{userId}")
)
```

Java:
``` java
.exec(jdbc("parameterized query")
    .queryP("SELECT * FROM TEST_TABLE WHERE id = {id}")
    .params(Map.of("id", "#{userId}")))
```

## Example Scenarios
Examples [here](https://github.com/galax-io/gatling-jdbc-plugin/tree/master/src/test)
