# Data Model: Statement Concurrency & Resource-Safety Hardening

**Date**: 2026-07-13 | **Plan**: [plan.md](plan.md)

No new data structures are introduced. The feature verifies invariants over the
existing model. Entities from the spec map to existing types as follows.

## Entity mapping

### Statement operation (spec: "one database action a virtual user performs")

| Aspect | Existing implementation |
|--------|------------------------|
| Kinds | raw SQL (`JDBCClient.executeRaw`), query (`executeSelect`), update/insert (`executeUpdate`), stored-procedure call (`call`), batch (`batch`) — `src/main/scala/org/galaxio/gatling/jdbc/db/JDBCClient.scala` |
| Ownership | Each operation = exactly one `Future` on the blocking pool; all JDBC work for the operation happens synchronously inside it (post-#59 invariant) |
| Timeout attribute | `queryTimeout: Option[FiniteDuration]` on `JDBCClient`, normalized to `queryTimeoutSeconds: Option[Int]` (sub-second rounds up to 1s) |

### Parameter binding (spec: "one value ↔ one position; unit whose overlap is eliminated")

| Aspect | Existing implementation |
|--------|------------------------|
| Value type | `ParamVal` ADT — `IntParam`, `LongParam`, `DoubleParam`, `StrParam`, `DateParam`, `BooleanParam`, `UUIDParam`, `NullParam` (`db/package.scala`) |
| Position resolution | `Interpolator.InterpolatorCtx.m: Map[String, List[Int]]` — named placeholder → 1-based JDBC indexes (`JDBCClient.scala:14-38`) |
| Binding execution | `statements.PreparedStatementOps.setParams` / `CallableStatementOps.setParams` — synchronous `foreach`, single thread (`statements.scala`) |
| **Invariant under test** | Max concurrent setter/`registerOutParameter` entry per statement == 1; each position bound/registered exactly once with its declared value; cross-position order unspecified (FR-001/FR-002) |

### Managed resource (spec: "pooled artifact released exactly once on all paths")

| Aspect | Existing implementation |
|--------|------------------------|
| Kinds | `Connection` (HikariCP pool), `Statement`/`PreparedStatement`/`CallableStatement`, `ResultSet`, `DisableAutoCommit` guard (batch) |
| Lifecycle | `scala.util.Using.Manager` registers each; releases LIFO exactly once on success, async failure, and synchronous throw (`JDBCClient.scala:53-93`) |
| **Invariant under test** | After sync-throw failure: original exception surfaces in `Failure` (non-fatal release failures suppressed, per `Using` severity ranking); each resource's `close()` invoked exactly once (counting proxies); Hikari active-connection count returns to 0 (FR-005/FR-006) |

### Query timeout (spec: "protocol-level max duration, extended to batches")

| Aspect | Existing implementation |
|--------|------------------------|
| DSL source | `JdbcProtocolBuilder…queryTimeout(FiniteDuration)` (Scala), `JdbcProtocolBuilderConnectionSettingsStep.queryTimeout(Duration)` (Java facade) |
| Wiring | Builder → `JdbcProtocol` → `JDBCClient` (`JdbcProtocol.scala:36`) |
| Application | `stmt.setQueryTimeout` in `withStatement`, `withPreparedStatement`, `withCallableStatement`, and per batch statement (`JDBCClient.scala:64,75,89,169`) |
| **Invariant under test** | Slow batch aborts as `Failure` within timeout + margin; unset timeout ⇒ unchanged behavior (FR-003/FR-004) |

## State transitions

Operation lifecycle (unchanged, verified):

```text
acquire connection → [batch only: disable autoCommit] → prepare statement
  → set timeout → bind params (SEQUENTIAL) → execute → read results
  → [batch only: commit | rollback] → release all resources (ALWAYS, exactly once)
  → consumer(Try[result]) → restore autoCommit → return connection to pool
```

Failure at ANY step short-circuits to the release path with the original error
preserved in the `Try`.

## Validation rules (from FRs)

- FR-001/FR-002: binding/registration serialized per statement; every position exactly
  once with its declared value (no cross-position order guarantee).
- FR-003/FR-004: timeout applied to batch statements iff configured.
- FR-005/FR-006: release exactly once on every path; original error preserved for
  non-fatal cleanup failures (cleanup error suppressed).
- FR-007: no signature/default/observable-behavior change for working scenarios.
- FR-008: every invariant above pinned by a test on a real DB path.
