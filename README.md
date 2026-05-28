# Gatling JDBC Plugin

[![CI](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.galaxio/gatling-jdbc-plugin_2.13.svg?color=success)](https://search.maven.org/search?q=org.galaxio.gatling-jdbc-plugin)
[![codecov](https://codecov.io/github/galax-io/gatling-jdbc-plugin/coverage.svg?branch=main)](https://codecov.io/github/galax-io/gatling-jdbc-plugin?branch=main)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

JDBC protocol plugin for [Gatling](https://gatling.io/) load testing framework. Execute SQL queries, inserts, updates, batch operations, and stored procedures against any JDBC-compatible database with connection pooling (HikariCP) and result checks.

## Table of Contents

- [Compatibility](#compatibility)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Protocol Configuration](#protocol-configuration)
- [Actions](#actions)
- [Checks](#checks)
- [Transactions](#transactions)
- [Session Variables](#session-variables)
- [Examples](#examples)
- [Contributing](#contributing)
- [License](#license)

## Compatibility

| Plugin Version | Gatling | Scala | Java |
|---|---|---|---|
| 0.x.y-latest | 3.13.x | 2.13 | 17+ |
| 0.x.y | 3.11.x | 2.13 | 17+ |

> **Branch strategy:** `main` targets Gatling 3.11.x, `latest/gatling` targets Gatling 3.13.x.

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
        .check(allResults.saveAs("rows"))
    )

  setUp(scn.inject(atOnceUsers(1))).protocols(dbConf)
}
```

### Minimal Scenario — Java

```java
import static org.galaxio.gatling.jdbc.javaapi.JdbcDsl.*;
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
              .check(allResults().saveAs("rows"))
      );

  { setUp(scn.injectOpen(atOnceUsers(1)).protocols(dbConf)); }
}
```

### Minimal Scenario — Kotlin

```kotlin
import org.galaxio.gatling.jdbc.javaapi.JdbcDsl.*
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
              .check(allResults().saveAs("rows"))
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
  .check(allResults.saveAs("rows"))
```

### Parameterized Query

```scala
jdbc("find user")
  .queryP("SELECT * FROM users WHERE id = {id} AND status = {status}")
  .params("id" -> "#{userId}", "status" -> "active")
```

### Insert

```scala
jdbc("insert user")
  .insertInto("users", "id", "name", "email")
  .values(Map("id" -> 1, "name" -> "#{userName}", "email" -> "#{email}"))
```

### Transaction

Execute multiple SQL statements atomically (auto-rollback on failure):

```scala
jdbc("transfer funds").transaction(
  "UPDATE accounts SET balance = balance - 100 WHERE id = 1",
  "UPDATE accounts SET balance = balance + 100 WHERE id = 2",
  "INSERT INTO audit_log (action) VALUES ('transfer')",
)
```

## Checks

| Check | Description |
|-------|-------------|
| `allResults` / `allResults()` | Full result set |
| `row(index)` | Single row by zero-based index |
| `column(name)` | All values from a column |
| `cell(name, rowIndex)` | Single value at column + row |
| `simpleCheck(predicate)` | Custom boolean predicate |

### Scala

```scala
jdbc("select users")
  .query("SELECT ID AS USER_ID, NAME FROM USERS ORDER BY ID")
  .check(
    cell("NAME", 0).is("Alice"),
    row(0).saveAs("firstRow"),
    column("USER_ID").saveAs("userIds"),
    allResults.saveAs("rows"),
  )
```

### Java

```java
jdbc("select users")
    .query("SELECT ID AS USER_ID, NAME FROM USERS ORDER BY ID")
    .check(
        cell("NAME", 0).saveAs("firstName"),
        row(0).saveAs("firstRow"),
        column("USER_ID").saveAs("userIds"),
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
    .insertInto("USERS", "id", "name", "active")
    .values(Map.of(
        "id", 1,
        "name", "#{userName}",
        "active", true
    ));
```

## Transactions

Execute multiple SQL statements atomically:

```scala
jdbc("transfer funds").transaction(
  "UPDATE accounts SET balance = balance - 100 WHERE id = 1",
  "UPDATE accounts SET balance = balance + 100 WHERE id = 2",
)
```

If any statement fails, the entire transaction is rolled back. Gatling EL expressions are supported.

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

# Run integration tests (requires Docker)
sbt "Test / testOnly -- -n org.galaxio.gatling.jdbc.tags.DockerTest"

# Run Gatling example simulation (H2)
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"

# Check formatting
sbt scalafmtCheckAll

# Format code
sbt scalafmtAll
```

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
