# Phase 0 Research: Runtime Correctness — Batch Execution & ResultSet Mapping

**Feature**: `003-batch-resultset-correctness` | **Date**: 2026-07-19 | **Revised**: 2026-07-21 (Codex review + cross-artifact analysis remediation)

Every decision below follows one ladder set by the spec: prefer loud, deterministic
failure over silent wrong data; prefer additive API evolution over signature change;
prefer the smallest change that closes the issue (constitution I, V).

## R0. Audit evidence re-verified against current `main`

**Decision**: Six of seven issues reproduce unchanged; #84 is half-fixed and the plan
targets the remaining rollback-side masking — including two deeper failure modes found
in review (nested-`Try` flattening and the auto-commit-restore commit, see R6).

**Rationale**: The issues cite audit baseline `a8d0401`. Since then the batch path was
rewritten around `scala.util.Using` (feature 001/002). Verified per file:

| Issue | Evidence in current code | Status |
|---|---|---|
| #84 | `JDBCClient.scala:175` wraps the statement in `Using(...)` → close failures suppress onto the primary per `Using` semantics — but only while the primary is **thrown**. The batch flow instead *returns* `Failure(primary)` as a value, so: (a) `JDBCClient.scala:188` `Try(conn.rollback()).flatMap(_ => Failure(exception))` replaces the primary with a rollback failure; (b) the outer `Using.Manager` treats the returned `Failure` as a successful result — if releasing resources (auto-commit restore, connection close) then throws, the manager's failure **replaces** the returned value and `.flatten` never sees the primary; (c) worst: after a failed rollback, `DisableAutoCommit.close` calls `setAutoCommit(true)`, and JDBC **commits the active transaction** on that transition — KO reported, partial batch persisted | Live, three failure modes |
| #86 | `JDBCClient.scala:107` `use(stmt.executeQuery).iterator.toList` — unconditional full materialization, even when `DBQueryAction.checks` is empty. Additionally the PostgreSQL driver buffers the entire result client-side unless the query runs with auto-commit off + forward-only cursor + fetch size — so avoiding retention alone does not bound memory on PG | Live |
| #87 | `db/package.scala:46` stores `resultSet.getObject(i)`; `Using.Manager` closes ResultSet/Statement/Connection when `withPreparedStatement` returns, **before** `consumer(result)` runs — Blob/Clob locators are dead by check time | Live |
| #88 | `executeUpdate`/`executeRaw`/`call` never commit; `JdbcProtocolBuilderBase.hikariConfig(cfg)` accepts arbitrary config, so `cfg.setAutoCommit(false)` → OK reported, Hikari rolls back the dirty connection on pool return. The step-builder path never touches autoCommit (Hikari default `true`) so only the custom-config entry is exposed. The config has **no working use case** in this plugin: there is no commit DSL, connections are not pinned to virtual users, and Hikari's rollback-on-checkin fires between any two actions — a later `rawSql("COMMIT")` can never reach an earlier action's transaction | Live |
| #122 | `db/package.scala:46` `md.getColumnName(i)` | Live |
| #123 | `db/package.scala:45-47` `Map + (label -> value)` — last duplicate wins silently | Live |
| #124 | `DBBatchAction.scala:35/45/55` and `DBInsertAction.scala:28` interpolate resolved `tableName` and column names directly into SQL text. Scope caveat: only **table names** are `Expression[String]` (feeder-reachable); column names (`Columns(names: String*)`, update SET keys) are static `String`s fixed at build time | Live (see R7 scope) |

**Alternatives considered**: Re-implement #84 close-path handling too — rejected: `Using`
already provides suppression for close **once the primary is thrown, not returned**; R6
restructures the flow to rely on exactly that. A regression test still covers both halves.

## R1. Alias fix (#122): `getColumnLabel`, no case normalization

**Decision**: `record` keys switch from `md.getColumnName(i)` to `md.getColumnLabel(i)`,
used verbatim — no case folding, no normalization. Per-engine case behavior (H2
upper-cases unquoted identifiers, PostgreSQL lower-cases them) is documented and asserted
in tests on both engines.

**Rationale**: JDBC defines `getColumnLabel` as "the label suggested by the SQL AS
clause, or the column name when there is no AS" — exactly the spec's contract (FR-004).
For every non-aliased query `getColumnLabel == getColumnName`, so existing sessions keyed
by physical names are untouched; only aliased columns change keys, which is the defect
being fixed. Normalizing case would change keys for *all* existing users — a real
breaking change hiding inside a bug fix.

**Alternatives considered**: Lower-casing all keys (predictable cross-engine, but breaks
every existing H2 user); configurable case mode (config surface for a problem
documentation solves — premature, constitution V).

## R2. Duplicate labels (#123): fail loud with a dedicated exception

**Decision**: Compute the label list once per ResultSet from metadata (hoisted out of the
per-row loop). If labels are not unique, fail the operation with a dedicated
`DuplicateColumnLabelException` naming the duplicated label(s). Detection happens once,
before the first row is mapped — **on every execution path, including the no-check
discard path** (spec edge case: duplicates must fail even when no check references
them).

**Rationale**: Spec FR-005 fixed rejection as the behavior (rationale recorded in spec
Assumptions: simplest deterministic contract, trivial workaround — alias uniquely).
A named exception type makes the KO message actionable and the regression test precise.
Hoisting metadata reading is required for one-shot detection and removes per-row
metadata calls — a small efficiency win with zero behavior change for unique-label
queries. Validating on the discard path too costs one metadata read and keeps FR-005
free of a silent carve-out.

**Alternatives considered**: Duplicate-preserving representation (qualified keys or
indexed keys) — new observable key format, API-surface growth, rejected in spec; warn-and-
last-wins — still silent data loss, violates FR-005; skipping validation when no checks
consume rows — contradicts the spec's own edge case.

## R3. LOB detachment (#87): materialize inside the ResultSet scope

**Decision**: `record` detaches driver-managed values while the ResultSet is open:

| JDBC type | Stored in session as | Note |
|---|---|---|
| `java.sql.Blob` | `Array[Byte]` (`getBytes(1, length)`) | freed after copy |
| `java.sql.Clob` / `NClob` | `String` (`getSubString(1, length)`) | freed after copy |
| `java.sql.SQLXML` | `String` (`getString`) | freed after copy |
| `java.sql.Array` | `Vector[Any]` from `getArray` | elements mapped recursively if themselves LOBs; freed after copy |
| everything else | `getObject` unchanged | current behavior preserved |

`free()` runs in a `finally`-equivalent path: locators are **not** `AutoCloseable` and
are invisible to the surrounding `Using.Manager`, so a copy failure must still release
the locator, with the release failure attached as suppressed to the primary copy
failure — never replacing it. LOBs larger than `Int.MaxValue` bytes/chars fail with a
clear exception (a load-test check on a >2 GB LOB is a test-design error, not a
supported path).

**Rationale**: Values must outlive the connection (spec FR-006); copying while the
ResultSet is open is the only portable way. Non-LOB types keep `getObject` so the
change is invisible outside the locator types — which today are *always* unusable
by check time, so this is a pure defect fix.

**Alternatives considered**: Keeping resources open until checks finish (inverts the
resource lifecycle fixed in feature 001, leaks on check crash — violates constitution
III); streaming handles into the session (session values must be immutable and
shareable; a stream is neither); registering locators with `Using.Manager` (they are
not `AutoCloseable`; a wrapper adds surface for no gain over try/finally).

## R4. Bounded retrieval (#86): streaming discard path + strict opt-in row cap

**Decision**: Three parts:

1. **No-check discard path**: `DBQueryAction` with `checks.isEmpty` calls a new
   `JDBCClient.executeSelectDiscard(sql, params, maxRows)` that drains the ResultSet
   (row count only, no row maps) inside the resource scope. To actually bound driver
   memory on PostgreSQL, the drain runs inside a **plugin-managed read transaction** —
   the same per-connection auto-commit scope the batch path uses (R6's transaction
   helper) — with a forward-only statement and a fetch-size hint: pgjdbc only streams
   with auto-commit off; H2 is indifferent. Duplicate-label validation still runs once
   before draining (R2). Timing semantics unchanged — the query still fully executes
   and transfers, so DB-side load is identical.
2. **Opt-in cap**: new DSL `.maxRows(n)` on the query builder (Scala + Java facade).
   The cap applies on **every** path — materializing and discard alike (a cap silently
   ignored on no-check queries would be exactly the silent-behavior class this milestone
   kills). Exceeding the cap fails the request (KO) with an explicit message. A
   driver-side transfer guard is set best-effort via `setMaxRows(cap + 1)`, skipped at
   `Int.MaxValue` (overflow) — `setLargeMaxRows` is deliberately avoided: drivers
   without it (pgjdbc) raise SQLFeatureNotSupportedException with SQLSTATE 0A000, which
   HikariCP treats as a connection error state and poisons the pooled connection
   (found by the PostgreSQL discard-path regression test). Correctness always comes
   from counting while reading, never from the hint.
3. **Default unchanged**: no cap configured + checks present → today's full
   materialization (spec Assumptions, compat).

**Rationale**: Discard-when-no-checks is observably identical (no consumer of rows
exists) but bounds memory for the pure-load use case that motivated the issue — and
only genuinely bounds it on PostgreSQL when the drain is transaction-scoped, hence the
reuse of the batch path's transaction helper. KO on overflow (not truncation) follows
the milestone's theme — truncated check input is silent wrong data. New client method
instead of changing `executeSelect`'s public shape keeps compatibility (constitution I).

**Alternatives considered**: Default-on cap (changes observable results of existing
check-consuming queries — breaking); truncation on overflow (silent wrong check input);
`fetchSize` as a bare hint without a transaction (does nothing on PG with auto-commit
on — verified pgjdbc behavior); lazy streams into checks (checks receive `List[Map]` by
published contract — `JdbcCheck = Check[List[Map[String, Any]]]`).

## R5. autoCommit=false (#88): reject before any resource exists

**Decision**: Two-layer, fail-fast validation:

1. `JdbcProtocol.newComponents` checks `protocol.hikariConfig.isAutoCommit` **before
   allocating anything** (executor, `HikariDataSource`) and throws
   `IllegalArgumentException` — no resources exist yet, nothing leaks on rejection.
2. `JDBCClient.apply` re-checks the built pool (`pool.isAutoCommit`) as a backstop for
   direct constructions (tests, programmatic use) and post-builder config mutation.

The message must be actionable: state that pooled connections make cross-action
transactions impossible (Hikari rolls back dirty connections on checkin; transactions
are per-connection and connections are never pinned to a virtual user) and name the
supported alternatives — the `batch` DSL (one plugin-managed transaction per request)
and a single-action `rawSql` block (`BEGIN; …; COMMIT`). Simulation fails at startup,
before any load runs.

**Rationale**: The non-batch paths (`executeUpdate`, `executeRaw`, `call`) are built on
auto-commit semantics; honoring `autoCommit=false` would require explicit
commit/rollback + state restore on every path for a configuration that has no working
use case (R0 #88 row). Reject-at-startup is the loud, small option (spec FR-001 allows
reject-or-manage; strictness ladder picks reject). Checking in `newComponents` *before*
allocation avoids leaking a started pool and executor on rejection; the `apply` backstop
keeps the guarantee for every construction path.

**Alternatives considered**: Explicit commit on every non-batch op (invasive, adds a
commit round-trip for all users to serve a config nobody can use meaningfully);
silently overriding to `true` (changes user config without telling them — the silent
category this milestone exists to kill); single check in `apply` only (would reject
after the pool has opened `minimumIdle` connections and the executor exists — resource
leak on the failure path, constitution III).

## R6. Rollback masking (#84): throw the primary, suppress cleanup, never commit-by-restore

**Decision**: Restructure the batch transaction flow (and reuse it for R4's discard
path) around three rules:

1. **The primary execution exception is thrown, not returned.** Inside the
   `Using.Manager` scope the failed batch **throws** the primary; `Using`'s own
   semantics then suppress any later close/release failures onto it. The current
   nested-`Try`-plus-`.flatten` shape is removed — a returned `Failure` is invisible to
   the manager and gets replaced by any cleanup exception.
2. **Rollback failure suppresses, never replaces**: rollback is attempted on failure;
   if it throws, the rollback exception is attached via `primary.addSuppressed` and the
   primary is still what propagates.
3. **A connection whose rollback failed is never restored through a committing
   transition.** JDBC commits the active transaction when `setAutoCommit(true)` is
   called mid-transaction — after a failed rollback that would persist a partial batch
   under a KO report. Instead the connection is evicted from the pool
   (`HikariDataSource.evictConnection`) and the auto-commit restore is skipped; the
   auto-commit scope helper (today's `DisableAutoCommit`) becomes failure-aware to
   support "restore on success / evict on failed rollback".

Regression test uses a proxy DataSource/Connection (extending the existing
`CloseCountingDataSource` test-support pattern) over a real H2 connection that makes
`executeBatch` throw `BatchUpdateException` and `rollback`/`close` throw independently,
asserting: the Future fails with `BatchUpdateException`; cleanup failures appear in
`getSuppressed`; and after a failed rollback **no partial batch data is visible from a
fresh connection** (eviction closes the connection, which terminates the transaction
without committing).

**Rationale**: `addSuppressed` is the JVM-standard idiom (it is what try-with-resources
and `Using` do); throwing the primary is the only shape under which `Using.Manager`
applies it. Eviction after a failed rollback is Hikari's supported way to retire a
connection in unknown state — the alternative (restore auto-commit) actively commits
the partial transaction.

**Alternatives considered**: Logging the rollback failure and dropping it (loses
information the report could carry); wrapping both in a new composite exception type
(changes the exception type consumers/tests see — needless surface); keeping the
nested-`Try` shape and manually merging manager failures (re-implements `Using`'s
suppression logic, worse).

## R7. Identifier validation (#124): allowlist grammar at resolve time

**Decision**: A small validator in the `db` layer (`SqlIdentifier.validate`), applied
where identifiers enter SQL text — `DBInsertAction` and
`DBBatchAction.resolveBatchAction`. Scope matches the actual DSL surface:

- **Table names** are `Expression[String]` — session/feeder-resolved per request; this
  is the dynamic (injectable) input. Validated on every resolution.
- **Column names** (`Columns(names: String*)`, batch-update SET keys) are static
  `String`s fixed at scenario-build time — not reachable from feeders with the current
  published API. They are validated too (guards typos and code-generated scenarios),
  but there is no feeder attack vector and no new `Expression`-typed column API is
  introduced (compatibility-sensitive, out of scope).

Grammar per segment, segments joined by `.` (up to 3: catalog.schema.object):

- unquoted: `[A-Za-z_][A-Za-z0-9_$]{0,127}`
- ANSI-quoted: `"…"` with `""` as the only escape; **no NUL, no `{`, no `}`**
- backtick-quoted: `` `…` `` with ` `` ` as the only escape; **no NUL, no `{`, no `}`**

`{`/`}` are excluded from quoted bodies because insert/update SQL derives `{column}`
placeholders from column names — a brace inside an identifier would terminate the
`Interpolator` placeholder early and produce malformed SQL. Excluding two characters is
documented; synthetic placeholder names decoupled from identifiers would be the
lifting-the-restriction path if ever needed.

Invalid identifier → `Validation.Failure` → existing `crashOnFailure` KO path; no SQL
string is ever built. The `where(...)` fragment of batch updates remains free-form by
contract (it is user-authored SQL, not an identifier) — documented explicitly.

**Rationale**: Allowlisting identifiers is robust where escaping is driver-specific and
error-prone. Accepting both ANSI and backtick quoting keeps existing usage on any
mainstream engine working while still rejecting everything that can alter statement
structure (whitespace, `;`, `--`, quotes that break out, braces that break
placeholders). Validating at resolve time means the KO is per-request with a precise
message and nothing reaches the driver (FR-008).

**Alternatives considered**: Quoting/escaping user input instead of validating (must
know the engine's quote rules — wrong per driver, and silently changes identifier case
semantics on PG/Oracle); validating only table names (static columns still interpolate
into the same SQL text — same malformed-SQL exposure from typos/codegen); synthetic
parameter binding names (touches the shared `Interpolator`/`withParamsMap` machinery
for a case documentation closes); a session-level opt-out flag (an escape hatch from a
safety check invites silent misuse; `rawSql` already exists for deliberate arbitrary
SQL); an `Expression`-typed columns API (new compatibility-sensitive surface with no
driving use case).

## R8. Test strategy per issue (constitution II)

**Decision**: Every fix lands with a real-database regression test in the same commit;
PostgreSQL (Testcontainers, existing `PostgreSQLIntegrationSpec` pattern) is added where
engine dialect matters, H2 otherwise:

| Issue | Test vehicle |
|---|---|
| #84 | Proxy DataSource over real H2 forcing `executeBatch` + `rollback`/`close` failures (extends `CloseCountingDataSource` pattern); asserts primary + suppressed + no-partial-persistence after failed rollback + non-batch close-failure suppression |
| #86 | H2: discard path drains a **1,000,000-row** `SYSTEM_RANGE` result (row count OK, no rows retained — SC-005 verified in CI); cap tests: KO at `cap + 1` on both paths (with and without checks), `cap == size`, zero rows, `Int.MaxValue` cap (no overflow); duplicate-label rejection on the discard path; checks-present path unchanged |
| #87 | H2 BLOB/CLOB/NCLOB/ARRAY (including array-of-LOB) read-after-completion checks; PostgreSQL `bytea`/`text`/`xml` coverage (H2's SQLXML support is weak — PG carries the XML assertion); null/empty LOB cases |
| #88 | Protocol-level test (`JdbcProtocolBuilderSpec` pattern): `hikariConfig` with `autoCommit=false` → rejected before executor/pool allocation; `JDBCClient.apply` backstop test; H2 test: OK update visible from a fresh connection under default config |
| #122 | Alias tests on H2 **and** PostgreSQL asserting keys are labels; case-convention assertions per engine |
| #123 | H2 JOIN with duplicate labels → `DuplicateColumnLabelException` naming the label; unique-alias workaround test |
| #124 | Pure validator unit tests (grammar accept/reject table incl. 128-char accept boundary and brace rejection) + H2 end-to-end: malicious **table** feeder value → KO, table intact, nothing executed; invalid static column name → KO |

SC-005 ("memory growth independent of row count") is verified in CI: the discard path
retains no per-row objects by construction and the 1M-row H2 drain exercises it at the
spec's stated scale; a heap-profiled measurement remains available as a manual exercise
via a no-check simulation over a large table.

**Rationale**: Real-database-first per constitution II; the one deliberate exception is
the #84 proxy, where a driver cannot be forced to fail rollback deterministically — the
proxy wraps a real H2 connection, matching the issue's own acceptance wording
("proxy test").
