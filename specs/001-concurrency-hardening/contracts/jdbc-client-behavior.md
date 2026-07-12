# Behavioral Contract: JDBC Client Execution Guarantees

**Date**: 2026-07-13 | **Plan**: [plan.md](plan.md)

This feature changes no public signatures. The contract below states the runtime
guarantees the plugin's DB layer makes to Gatling scenarios — the guarantees the
new tests pin down. Consumers: Scala DSL users, `javaapi` (Java/Kotlin) users.

## Unchanged public surface (FR-007)

- `JdbcProtocolBuilder` / `JdbcProtocolBuilderConnectionSettingsStep` — incl.
  `queryTimeout(FiniteDuration)` / `queryTimeout(java.time.Duration)`
- `JDBCClient.{executeRaw, executeSelect, executeUpdate, call, batch, close}` —
  signatures, `Try`-based consumer callbacks, `Future[U]` returns
- `ParamVal` ADT, `SQL` / `SqlWithParam`, check DSL

## Guarantee 1 — Serialized parameter binding (#120, FR-001)

For any single `PreparedStatement`:

- G1.1 At most one parameter-setter call (`setInt`/`setString`/…/`setObject`) is in
  progress at any instant.
- G1.2 Binding happens-before statement execution on the same thread.
- G1.3 Values land at the JDBC indexes the interpolator derived from the SQL's
  named placeholders; duplicate placeholder names bind every mapped index. Each index
  is bound exactly once with its declared value. Relative order between distinct
  indexes is unspecified (no driver requires one; adjusted after adversarial review).
- G1.4 A binding failure (e.g., missing binding for a placeholder) fails the whole
  operation with the original exception; the statement is never executed.

## Guarantee 2 — Serialized IN/OUT registration (#121, FR-002)

For any single `CallableStatement`:

- G2.1 At most one setter or `registerOutParameter` call is in progress at any
  instant; no IN/OUT overlap. Each IN index bound exactly once, each OUT index
  registered exactly once; relative order between distinct indexes is unspecified.
- G2.2 OUT-parameter registration and IN binding complete before execution.
- G2.3 `call` returns the OUT values by name; missing OUT placeholders raise
  `IllegalArgumentException` naming the missing parameters (existing behavior).

## Guarantee 3 — Batch honors queryTimeout (#83, FR-003/FR-004)

- G3.1 If `queryTimeout` is configured on the protocol, every statement a `batch`
  prepares has that timeout applied before execution.
- G3.2 A batch exceeding the timeout fails the operation (`Failure` → KO), within
  timeout + a small driver margin; the transaction is rolled back and resources
  released.
- G3.3 If no timeout is configured, batch semantics are byte-for-byte today's:
  no implicit timeout.
- G3.4 Sub-second configured timeouts round up to 1 second (existing normalization).

## Guarantee 4 — Release exactly once, error preserved (#100, FR-005/FR-006)

- G4.1 Every acquired resource (connection, statement, result set, autocommit
  guard) is released exactly once on success, on asynchronous failure, and on
  synchronous throw before any deferred work exists.
- G4.2 The caller's `Try` carries the ORIGINAL failure whenever release failures are
  ordinary (non-fatal) exceptions; those release failures are attached as suppressed.
  Per `scala.util.Using` severity ranking, fatal throwables raised during release
  (`VirtualMachineError`, `ControlThrowable`, `InterruptedException`, `LinkageError`)
  may take precedence over the original failure — the contract does not promise
  otherwise (adjusted after adversarial review).
- G4.3 After any failed operation, the connection returns to the pool: active
  connection count reaches 0 once the operation completes.
- G4.4 Repeated failing operations do not accumulate leaked resources (soak
  stability).

## Verification matrix

| Guarantee | Test artifact (planned) | Real-DB path |
|-----------|------------------------|--------------|
| G1.* | `StatementParamsConcurrencySpec` (proxy) + H2/PG value round-trip | H2 + PostgreSQL |
| G2.* | `StatementParamsConcurrencySpec` (proxy) + stored-proc under load | PostgreSQL (function), H2 alias |
| G3.* | `BatchQueryTimeoutSpec` | PostgreSQL (`pg_sleep`), H2 fast-path |
| G4.* | `ResourceReleaseOnSyncThrowSpec` — close-counting proxy resources (exactly-once per connection/statement), op-failure × release-failure case asserting primary vs suppressed, Hikari pool metrics, soak loop | H2 |
