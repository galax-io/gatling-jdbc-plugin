# Data Model: Runtime Correctness — Batch Execution & ResultSet Mapping

**Feature**: `003-batch-resultset-correctness` | **Date**: 2026-07-19 (revised 2026-07-21)

No persistent schema — this feature reshapes the in-memory data the plugin moves between
the database and Gatling sessions. Entities below are ordered by the flow: result
extraction → outcome reporting → guarded inputs.

## Result Row

One row of a query result as delivered to checks (`JdbcCheck = Check[List[Map[String, Any]]]` — published type, unchanged).

| Aspect | Rule | Source |
|---|---|---|
| Shape | `Map[String, Any]`, one entry per result column | existing |
| Key | Column **label** exactly as the driver reports it (`getColumnLabel`) — alias when `AS` is present, column name otherwise; no case normalization | FR-004, R1 |
| Key uniqueness | Labels must be unique within the row set; violation aborts the operation before the first row is mapped (`DuplicateColumnLabelException`) — enforced on **every** path, including the no-check discard path | FR-005, R2 |
| Values | Self-contained: safe to read after the operation completes (see Large-Object Value) | FR-006, R3 |
| Lifecycle | Built inside the `Using.Manager` scope while the ResultSet is open; immutable afterwards | constitution III |

**State transition**: metadata read once per ResultSet → label list validated (fail here
on duplicates — materializing and discard paths alike) → rows mapped with the
precomputed labels (or drained and counted on the discard path) → resources closed →
list/count handed on.

## Column Label

Author-visible key for a value in a Result Row.

- Derived per column from ResultSet metadata, never from the physical column name when an
  alias exists.
- Case follows the engine's reporting for the written query (H2 upper-cases unquoted,
  PostgreSQL lower-cases unquoted); documented, asserted in tests on both engines, never
  rewritten by the plugin.

## Large-Object Value

Detached representation of driver-managed values, materialized while the ResultSet is open (R3):

| JDBC source type | Session representation | Empty/null |
|---|---|---|
| `Blob` | `Array[Byte]` | `null` column → `null`; empty LOB → empty array |
| `Clob` / `NClob` | `String` | `null` → `null`; empty → `""` |
| `SQLXML` | `String` | `null` → `null` |
| `Array` | `Vector[Any]` (elements recursively detached, incl. array-of-LOB) | `null` → `null` |
| any other type | `getObject` result, unchanged | unchanged |

Validation and lifecycle rules:

- LOB length above `Int.MaxValue` → explicit failure (unsupported in a load-test check
  path).
- Locators are **not** `AutoCloseable` and are invisible to `Using.Manager`; each copy
  releases its locator in a `finally`-equivalent path. A release failure attaches to
  the primary copy failure via `addSuppressed` — it never replaces it and never leaks
  the locator.
- Detachment failures propagate as the operation's failure (KO), never as a partial row.

## Operation Outcome

The OK/KO verdict + timing logged per request (existing `executeNext`/`reportError` flow, semantics tightened):

- **OK invariant (writes)**: change is committed and visible to a fresh connection at
  report time. Enforced by construction: auto-commit connections for non-batch paths
  (protocol rejects `autoCommit=false` **before allocating the pool or executor**, with
  a `JDBCClient.apply` backstop — FR-001, R5), explicit commit in the plugin-managed
  batch transaction.
- **KO invariant (batch)**: reported cause is the primary execution exception, which is
  **thrown** through the resource scope (never returned as a value) so `Using`
  suppression applies; cleanup failures (rollback, close, auto-commit restore) ride
  along via `getSuppressed`, never replace it (FR-003, R6).
- **KO invariant (failed rollback)**: a connection whose rollback failed is evicted from
  the pool; the auto-commit restore is skipped because a mid-transaction
  `setAutoCommit(true)` **commits** — a KO must never persist partial batch work
  (FR-002, R6).

## Row Cap

Optional per-query bound on retained/read rows (FR-007, R4). All four configuration
combinations are defined — the cap is never silently ignored:

| Checks | `maxRows` | Behavior |
|---|---|---|
| present | absent | unlimited retention — today's behavior (compat default) |
| present | set | ≤ n rows materialized; overflow → KO naming the cap; no truncated data ever reaches checks |
| absent | absent | discard path: rows drained inside a plugin-managed read transaction (streams on PostgreSQL), counted, not retained — memory O(1) per query |
| absent | set | discard path **with the cap enforced while draining**; overflow → KO naming the cap |

- `maxRows` must be positive (`require`); `Int.MaxValue` is valid — the driver hint is
  skipped at that boundary so `cap + 1` cannot overflow.
- Driver-side transfer guard is `setMaxRows(cap + 1)`, best-effort; `setLargeMaxRows`
  is deliberately not used (pgjdbc raises SQLSTATE 0A000, which HikariCP treats as a
  connection error and poisons the connection). Correctness always comes from counting
  while reading.

## Dynamic Identifier

Table or column name entering SQL text at request/build time (FR-008, R7).

- **Table names**: `Expression[String]` — session/feeder-resolved, the dynamic
  (injectable) input; validated on every resolution.
- **Column names**: static `String`s (`Columns(...)`, update SET keys) fixed at
  scenario-build time — not feeder-reachable with the current published API; validated
  as a typo/codegen guard. No `Expression`-typed column API is introduced.
- Validated by `SqlIdentifier.validate` against the grammar in
  [contracts/identifier-grammar.md](contracts/identifier-grammar.md) **before** any SQL
  text is assembled; quoted segments exclude `{`/`}` (placeholder-collision rule).
- Does **not** apply to `where(...)` fragments (user-authored SQL by contract,
  documented) or `rawSql` (deliberate arbitrary SQL).
- Invalid → `Validation.Failure` → existing `crashOnFailure` KO path; request fails with
  the offending value quoted in the message; nothing reaches the driver.

## New Exception Types

Both live in the `db` layer, both surface through existing KO reporting (message =
`getMessage`):

| Type | Thrown when | Message must include |
|---|---|---|
| `DuplicateColumnLabelException` (`db/exceptions.scala`) | Result metadata contains a repeated label — on any path, incl. discard | every duplicated label |
| `InvalidSqlIdentifierException` (`db/SqlIdentifier.scala`; wrapped into `Validation.Failure` at action layer) | Identifier fails the grammar | the rejected value and the expected forms |

Additive public surface — documented in
[contracts/api-surface.md](contracts/api-surface.md).
