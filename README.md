# Gatling JDBC Plugin

[![CI](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.galaxio/gatling-jdbc-plugin_2.13.svg?color=success)](https://search.maven.org/search?q=org.galaxio.gatling-jdbc-plugin)
[![codecov](https://codecov.io/github/galax-io/gatling-jdbc-plugin/coverage.svg?branch=main)](https://codecov.io/github/galax-io/gatling-jdbc-plugin?branch=main)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

JDBC protocol plugin for [Gatling](https://gatling.io/) load testing framework. Execute SQL queries, inserts, updates, batch operations, raw SQL, and stored procedures against any JDBC-compatible database with connection pooling (HikariCP) and result checks.

## Table of Contents

- [Compatibility](#compatibility)
- [Installation](#installation)
- [Database Driver Dependencies](#database-driver-dependencies)
- [Quick Start](#quick-start)
- [Protocol Configuration](#protocol-configuration)
- [Actions](#actions)
- [Checks](#checks)
- [Session Variables](#session-variables)
- [Examples](#examples)
- [Contributing](#contributing)
- [License](#license)

## Compatibility

| Plugin Version | Gatling | Scala | Java |
|---|---|---|---|
| 1.x.y | 3.13.x | 2.13 | 11+ |
| 0.20.x | 3.13.x | 2.13 | 17+ |

> **Branch strategy:** `main` targets Gatling 3.13.x.

## Installation

### Scala (sbt)

```scala
libraryDependencies += "org.galaxio" %% "gatling-jdbc-plugin" % "<version>" % Test
```

### Java / Kotlin (Gradle Kotlin DSL)

```kotlin
gatling("org.galaxio:gatling-jdbc-plugin_2.13:<version>")
```

### Maven

```xml
<dependency>
  <groupId>org.galaxio</groupId>
  <artifactId>gatling-jdbc-plugin_2.13</artifactId>
  <version>${version}</version>
  <scope>test</scope>
</dependency>
```

## Database Driver Dependencies

This plugin does **not** bundle vendor JDBC drivers. You must add the driver for your database separately.

### PostgreSQL

```scala
// sbt
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.11" % Test
```
```kotlin
// Gradle Kotlin DSL
gatling("org.postgresql:postgresql:42.7.11")
```
```xml
<!-- Maven -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>42.7.11</version>
  <scope>test</scope>
</dependency>
```

### MySQL / MariaDB

```scala
libraryDependencies += "com.mysql" % "mysql-connector-j" % "9.3.0" % Test
```

### Microsoft SQL Server

```scala
libraryDependencies += "com.microsoft.sqlserver" % "mssql-jdbc" % "12.10.0.jre11" % Test
```

### Oracle

```scala
libraryDependencies += "com.oracle.database.jdbc" % "ojdbc11" % "23.7.0.25.01" % Test
```

> **Note:** The quick-start examples below use PostgreSQL. Add the corresponding driver dependency before running them.

## Quick Start

### Docker (local PostgreSQL)

```bash
docker run -d --name gatling-pg \
  -e POSTGRES_USER=test -e POSTGRES_PASSWORD=test -e POSTGRES_DB=test \
  -p 5432:5432 postgres:16
```

### Minimal Scenario — Scala

```scala
import org.galaxio.gatling.jdbc.Predef._
import io.gatling.core.Predef._

class JdbcSimulation extends Simulation {
  val dbConf = DB
    .url("jdbc:postgresql://localhost:5432/test")
    .username("test")
    .password("test")
    .maximumPoolSize(10)

  val scn = scenario("JDBC Query")
    .exec(
      jdbc("select users")
        .query("SELECT * FROM users WHERE id = 1")
        .check(simpleCheck(_.nonEmpty))
    )

  setUp(scn.inject(atOnceUsers(1))).protocols(dbConf)
}
```

### Minimal Scenario — Java

```java
import static org.galaxio.gatling.javaapi.JdbcDsl.*;
import static io.gatling.javaapi.core.CoreDsl.*;

public class JdbcSimulation extends Simulation {
  var dbConf = DB()
      .url("jdbc:postgresql://localhost:5432/test")
      .username("test")
      .password("test")
      .maximumPoolSize(10)
      .protocolBuilder();

  var scn = scenario("JDBC Query")
      .exec(
          jdbc("select users")
              .query("SELECT * FROM users WHERE id = 1")
              .check(simpleCheck(simpleCheckType.NonEmpty))
      );

  { setUp(scn.injectOpen(atOnceUsers(1)).protocols(dbConf)); }
}
```

### Minimal Scenario — Kotlin

```kotlin
import org.galaxio.gatling.javaapi.JdbcDsl.*
import io.gatling.javaapi.core.CoreDsl.*

class JdbcSimulation : Simulation() {
  val dbConf = DB()
      .url("jdbc:postgresql://localhost:5432/test")
      .username("test")
      .password("test")
      .maximumPoolSize(10)
      .protocolBuilder()

  val scn = scenario("JDBC Query")
      .exec(
          jdbc("select users")
              .query("SELECT * FROM users WHERE id = 1")
              .check(simpleCheck(simpleCheckType.NonEmpty))
      )

  init { setUp(scn.injectOpen(atOnceUsers(1)).protocols(dbConf)) }
}
```

## Protocol Configuration

### Scala

```scala
import org.galaxio.gatling.jdbc.Predef._
import scala.concurrent.duration._

val dataBase = DB
  .url("jdbc:postgresql://localhost:5432/test")
  .username("user")
  .password("pass")
  .maximumPoolSize(32)
  .blockingPoolSize(32)
  .queryTimeout(30.seconds)
```

### Java

```java
var dataBase = DB()
    .url("jdbc:postgresql://localhost:5432/test")
    .username("user")
    .password("pass")
    .maximumPoolSize(32)
    .blockingPoolSize(32)
    .queryTimeout(Duration.ofSeconds(30))
    .protocolBuilder();
```

### Kotlin

```kotlin
val dataBase = DB()
    .url("jdbc:postgresql://localhost:5432/test")
    .username("user")
    .password("pass")
    .maximumPoolSize(32)
    .blockingPoolSize(32)
    .queryTimeout(Duration.ofSeconds(30))
    .protocolBuilder()
```

### Connection Pool Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `maximumPoolSize` | 10 | Max connections in HikariCP pool |
| `minimumIdleConnections` | 10 | Min idle connections |
| `blockingPoolSize` | = `maximumPoolSize` | Fixed thread pool for blocking JDBC calls |
| `connectionTimeout` | 1 minute | Connection acquisition timeout |
| `queryTimeout` | None | Statement query timeout (per query) |

JDBC calls are blocking, so the plugin runs them on a dedicated executor. `blockingPoolSize` defaults to `maximumPoolSize` to prevent unbounded native thread growth.

### Custom HikariConfig

```scala
val hikariConfig = new HikariConfig()
hikariConfig.setJdbcUrl("jdbc:postgresql://localhost:5432/test")
hikariConfig.setMaximumPoolSize(16)

val dataBase = DB.hikariConfig(hikariConfig)
```

## Actions

### Query

```scala
jdbc("select users")
  .query("SELECT * FROM users WHERE status = 'active'")
  .check(simpleCheck(_.nonEmpty))
```

### Parameterized Query

Uses prepared statements. `{param}` placeholders are replaced with `?` at execution time.

```scala
jdbc("find user")
  .queryP("SELECT * FROM users WHERE id = {id} AND status = {status}")
  .params("id" -> "#{userId}", "status" -> "active")
```

### Insert

```scala
jdbc("insert user")
  .insertInto("users", Columns("id", "name", "email"))
  .values("id" -> 1, "name" -> "#{userName}", "email" -> "#{email}")
```

**Java / Kotlin:**

```java
jdbc("insert user")
    .insertInto("users", "id", "name", "email")
    .values(Map.of("id", 1, "name", "#{userName}", "email", "#{email}"))
```

### Raw SQL

Execute any SQL statement (DDL, DML, etc.) without result mapping:

```scala
jdbc("create table")
  .rawSql("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, name VARCHAR(100))")
```

**Java / Kotlin:**

```java
jdbc("create table")
    .rawSql("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, name VARCHAR(100))")
```

### Batch Operations

Execute multiple insert/update operations in a single batch:

**Scala:**

```scala
jdbc("batch insert").batch(
  insertInto("users", Columns("id", "name")).values("id" -> 1, "name" -> "Alice"),
  insertInto("users", Columns("id", "name")).values("id" -> 2, "name" -> "Bob"),
)
```

```scala
jdbc("batch update").batch(
  update("users").set("name" -> "Updated").where("id = 1"),
  update("users").set("name" -> "Updated2").where("id = 2"),
)
```

**Java / Kotlin:**

```java
jdbc("batch insert").batch(
    insertInto("users", "id", "name").values(Map.of("id", 1, "name", "Alice")),
    insertInto("users", "id", "name").values(Map.of("id", 2, "name", "Bob"))
)
```

### Stored Procedures

Call stored procedures with IN and optional OUT parameters:

**Scala:**

```scala
jdbc("call procedure")
  .call("my_procedure")
  .params("inParam" -> "#{value}")
```

With OUT parameters (values are stored in the Gatling session):

```scala
jdbc("call with out")
  .call("my_procedure")
  .params("inParam" -> "#{value}")
  .outParams("outResult" -> java.sql.Types.INTEGER)
```

**Java / Kotlin:**

```java
jdbc("call procedure")
    .call("my_procedure")
    .params(Map.of("inParam", "#{value}"))
    .outParams(Map.of("outResult", java.sql.Types.INTEGER))
```

After execution, OUT parameter values are available in the session via `#{outResult}`.

## Checks

| Check | Description |
|-------|-------------|
| `allResults` / `allResults()` | Full result set as `List[Map[String, Any]]` |
| `simpleCheck(predicate)` | Custom boolean predicate over the result set |
| `simpleCheck(simpleCheckType.NonEmpty)` | Built-in Java/Kotlin check: result is non-empty |
| `simpleCheck(simpleCheckType.Empty)` | Built-in Java/Kotlin check: result is empty |

### Scala

```scala
jdbc("select users")
  .query("SELECT * FROM users")
  .check(
    simpleCheck(_.nonEmpty),
    allResults.saveAs("rows"),
  )
```

### Java

```java
jdbc("select users")
    .query("SELECT * FROM users")
    .check(
        simpleCheck(simpleCheckType.NonEmpty),
        allResults().saveAs("rows")
    );
```

## Session Variables

The plugin supports [Gatling Expression Language (EL)](https://docs.gatling.io/reference/script/core/session/el/) in SQL queries. Use `#{variableName}` to reference session values.

### query() with EL

```scala
exec(session => session.set("tableName", "USERS"))
.exec(jdbc("dynamic query").query("SELECT * FROM #{tableName} WHERE id = #{id}"))
```

### queryP() — Prepared Statements

- `{param}` in SQL — prepared statement placeholder (replaced with `?`)
- `"#{var}"` in `.params()` — Gatling EL, resolves from session at runtime

```scala
jdbc("parameterized query")
  .queryP("SELECT * FROM users WHERE id = {id}")
  .params("id" -> "#{userId}")
```

### Java/Kotlin Typed Values

```java
jdbc("insert user")
    .insertInto("users", "id", "name", "active")
    .values(Map.of(
        "id", 1,
        "name", "#{userName}",
        "active", true
    ));
```

## Examples

- [Scala](src/test/scala/org/galaxio/performance/jdbc/test)
- [Java](src/test/java/org/galaxio/performance/jdbc/test)
- [Kotlin](src/test/kotlin/org/galaxio/performance/jdbc/test)

## Contributing

```bash
# Build
sbt compile

# Run unit tests
sbt test

# Run all tests (unit + integration; integration tests start PostgreSQL via Testcontainers)
sbt test

# Run Gatling example simulation (H2)
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"

# Check formatting
sbt scalafmtCheckAll

# Format code
sbt scalafmtAll
```

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
