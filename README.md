# Gatling JDBC Plugin

[![CI](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/galax-io/gatling-jdbc-plugin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.galaxio/gatling-jdbc-plugin_2.13.svg?color=success)](https://search.maven.org/search?q=org.galaxio.gatling-jdbc-plugin)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)
[![codecov](https://codecov.io/github/galax-io/gatling-jdbc-plugin/coverage.svg?branch=main)](https://codecov.io/github/galax-io/gatling-jdbc-plugin?branch=main)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

JDBC protocol plugin for [Gatling](https://gatling.io/) load testing framework. Execute SQL queries, inserts, updates, batch operations, and stored procedures against any JDBC-compatible database with connection pooling (HikariCP) and result checks.

Requires Scala 2.13, Java 17+, Gatling 3.11+.

## Installation

**sbt**:
```scala
libraryDependencies += "org.galaxio" %% "gatling-jdbc-plugin" % "<version>" % Test
```

**Maven**:
```xml
<dependency>
  <groupId>org.galaxio</groupId>
  <artifactId>gatling-jdbc-plugin_2.13</artifactId>
  <version>${version}</version>
  <scope>test</scope>
</dependency>
```

**Gradle (Kotlin DSL)**:
```kotlin
gatling("org.galaxio:gatling-jdbc-plugin_2.13:<version>")
```

## Protocol Configuration

### Scala

```scala
import org.galaxio.gatling.jdbc.Predef._

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
// ... any HikariCP settings

val dataBase = DB.hikariConfig(hikariConfig)
```

## Using Gatling Session Variables

The plugin supports [Gatling Expression Language (EL)](https://docs.gatling.io/reference/script/core/session/el/) in SQL queries.
Use `#{variableName}` to reference values stored in the Gatling session.

### `query()` with EL

```scala
exec(session => session.set("tableName", "USERS"))
.exec(jdbc("dynamic query").query("SELECT * FROM #{tableName} WHERE id = #{id}"))
```

### `queryP()` with EL

`queryP` uses two different syntaxes:
- `{param}` in SQL string — prepared statement placeholder (replaced with `?`)
- `"#{var}"` in `.params()` — Gatling EL, resolves value from session at runtime

```scala
exec(
  jdbc("parameterized query")
    .queryP("SELECT * FROM TEST_TABLE WHERE id = {id}")
    .params("id" -> "#{userId}")
)
```

### Java/Kotlin typed values in `params()` and `values()`

Java and Kotlin map-based DSL methods preserve literal types such as `Boolean`, numeric values, UUIDs, and dates.
String values still support Gatling EL, so you can mix typed literals and session expressions in the same map.

```java
jdbc("insert user")
    .insertInto("USERS", "id", "name", "active")
    .values(Map.of(
        "id", 1,
        "name", "#{userName}",
        "active", true
    ));
```

## Inspecting Query Results

JDBC query checks operate on a result set represented as `List[Map[String, Any]]`.

### Available Result Checks

| Check | Description |
|-------|-------------|
| `allResults` / `allResults()` | Full result set |
| `row(index)` | Single row by zero-based index |
| `column(name)` | All values from a column |
| `cell(name, rowIndex)` | Single value at column + row |
| `simpleCheck(predicate)` | Custom boolean predicate |

When a row index or column name is invalid, the check fails with a descriptive error message.

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

## Transactions

Execute multiple SQL statements in a single JDBC transaction with automatic commit/rollback.
If any statement fails, the entire transaction is rolled back.

### Scala

```scala
jdbc("transfer funds").transaction(
  "UPDATE accounts SET balance = balance - 100 WHERE id = 1",
  "UPDATE accounts SET balance = balance + 100 WHERE id = 2",
  "INSERT INTO audit_log (action) VALUES ('transfer')",
)
```

### Java / Kotlin

```java
jdbc("transfer funds").transaction(
    "UPDATE accounts SET balance = balance - 100 WHERE id = 1",
    "UPDATE accounts SET balance = balance + 100 WHERE id = 2",
    "INSERT INTO audit_log (action) VALUES ('transfer')"
);
```

Gatling EL expressions (`#{varName}`) are supported in transaction statements.

## Connection Pool Metrics

HikariCP pool metrics (active, idle, waiting, total connections) are logged automatically when the simulation ends.
This helps diagnose pool exhaustion or connection leaks during load tests.

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
