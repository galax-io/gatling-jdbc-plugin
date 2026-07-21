# Tasks: Runtime Correctness — Batch Execution & ResultSet Mapping

**Input**: Design documents from `/specs/003-batch-resultset-correctness/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)

**Tests**: INCLUDED — constitution Principle II and spec FR-009 mandate a real-database regression test in the same commit as each behavior change. Write each story's tests first, watch them fail, then implement.

**Organization**: One phase per user story (priority order from spec.md). Commit discipline per constitution IV: **1 issue = 1 Conventional Commit**, green on `sbt scalafmtCheckAll scalafmtSbtCheck compile test`. US2 contains two issues → two commits; US4's tasks fold into one commit. Every commit body must note its observable behavior change for release notes (see [contracts/api-surface.md](contracts/api-surface.md)).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1–US6 from spec.md

## Phase 1: Setup

**Purpose**: Confirm clean baseline — existing project, no scaffolding needed.

- [X] T001 Verify baseline gate green before any change: `sbt scalafmtCheckAll scalafmtSbtCheck compile test` and `sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"` (repo root; record result)

---

## Phase 2: Foundational

**Purpose**: None required — the six stories share no blocking prerequisite; all cross-story constraints are file-ordering only (see Dependencies). The transaction-scope helper (R6) is built inside US3 and reused by US4, which is why US3 precedes US4 in the `JDBCClient.scala` sequence. Proceed directly to Phase 3.

---

## Phase 3: User Story 1 — Reported success means persisted data (Priority: P1) 🎯 MVP

**Goal**: OK on a write ⇒ committed and visible; `autoCommit=false` pool config rejected loudly at startup, before any resource is allocated (#88, FR-001/FR-002, R5).

**Independent Test**: Build protocol from `hikariConfig` with `autoCommit=false` → construction fails with a clear message and no leaked pool/executor; default-config update reported OK is visible from a fresh H2 connection.

### Tests (write first, must fail)

- [X] T002 [US1] Regression specs: (a) `JDBCClient` construction over an H2 `HikariDataSource` with `setAutoCommit(false)` throws `IllegalArgumentException` naming the batch/`rawSql` alternatives — new `src/test/scala/org/galaxio/gatling/jdbc/db/AutoCommitGuardSpec.scala` (use `db/testsupport/H2.scala` config helpers); (b) with default config, an `executeUpdate` reported OK is visible from a separate fresh connection; (c) batch path still commits/rolls back correctly (guard against regression); (d) protocol path: `newComponents` over a protocol built via `JdbcProtocolBuilderBase.hikariConfig(cfg)` with `autoCommit=false` rejects **before** executor/pool allocation (no `HikariDataSource` opened, nothing to close) — extend `src/test/scala/org/galaxio/gatling/jdbc/protocol/JdbcProtocolBuilderSpec.scala`

### Implementation

- [X] T003 [US1] Two-layer validation per R5: (1) pre-allocation check in `JdbcProtocol.newComponents` — inspect `protocol.hikariConfig.isAutoCommit` and throw **before** creating the executor or `HikariDataSource` (`src/main/scala/org/galaxio/gatling/jdbc/protocol/JdbcProtocol.scala`); (2) backstop in `JDBCClient.apply` — if `pool.isAutoCommit == false` throw (`src/main/scala/org/galaxio/gatling/jdbc/db/JDBCClient.scala`), covering direct constructions and post-builder config mutation. Message must be actionable: state that pooled connections make cross-action transactions impossible (Hikari rolls back dirty connections on return) and name the supported alternatives — `batch` DSL (one transaction per request, plugin-managed) and a single `rawSql` block (`BEGIN; …; COMMIT` in one action). Commit `fix(db): reject autoCommit=false pool configuration at startup (#88)` (body: behavior-change note)

**Checkpoint**: US1 independently green — `sbt "testOnly *AutoCommitGuard* *JdbcProtocolBuilder*"` + full gate.

---

## Phase 4: User Story 2 — Query results keyed by the labels the author wrote (Priority: P2)

**Goal**: Result keys = column labels verbatim (`AS` honored); duplicate labels fail loud with every duplicate named (#122 + #123, FR-004/FR-005, R1/R2). Two commits, #122 first.

**Independent Test**: Aliased query on H2 + PostgreSQL yields alias keys (engine case asserted); JOIN with duplicate labels → `DuplicateColumnLabelException` naming the label, before any row maps.

### Tests (write first, must fail)

- [X] T004 [P] [US2] Alias-mapping spec on H2: `SELECT id AS customer_id` keyed `CUSTOMER_ID` (H2 upper-case rule), quoted alias verbatim, non-aliased query keys unchanged vs current behavior — new `src/test/scala/org/galaxio/gatling/jdbc/db/ResultSetLabelSpec.scala`
- [X] T005 [P] [US2] PostgreSQL alias/case assertions (lower-case unquoted rule, quoted alias verbatim) — extend `src/test/scala/org/galaxio/gatling/jdbc/db/PostgreSQLIntegrationSpec.scala` (Testcontainers pattern already present)
- [X] T006 [P] [US2] Duplicate-label spec on H2: two-table JOIN yielding `id` twice → operation fails with `DuplicateColumnLabelException` naming `id`; same JOIN with unique aliases succeeds; alias-collides-with-other-column case also rejected (edge case from spec) — new `src/test/scala/org/galaxio/gatling/jdbc/db/DuplicateColumnLabelSpec.scala`

### Implementation

- [X] T007 [US2] Switch `record` to labels + hoist metadata: read `getColumnLabel` for all columns once per ResultSet (list computed before row loop in `ResultSetOps.iterator`), map rows from the precomputed labels — `src/main/scala/org/galaxio/gatling/jdbc/db/package.scala`. Commit `fix(db): key result rows by column label, not physical name (#122)` (T004+T005 in same commit; body: behavior-change note)
- [X] T008 [US2] Add `DuplicateColumnLabelException` (message lists every duplicated label) in new `src/main/scala/org/galaxio/gatling/jdbc/db/exceptions.scala`; uniqueness check on the hoisted label list before first row, exposed so the discard path can reuse it in US4 — `src/main/scala/org/galaxio/gatling/jdbc/db/package.scala`. Commit `fix(db): fail loud on duplicate ResultSet labels (#123)` (T006 in same commit; body: behavior-change note)

**Checkpoint**: `sbt "testOnly *ResultSetLabel* *DuplicateColumnLabel*"` + PG slice + full gate green.

---

## Phase 5: User Story 3 — Failure reports carry the root cause (Priority: P3)

**Goal**: Batch KO always reports the primary execution exception; rollback/close failures ride in `getSuppressed`; a failed rollback never turns into a partial commit (#84, FR-002/FR-003, R6).

**Independent Test**: Proxy-forced `executeBatch` failure + failing rollback/close → Future fails with the original `BatchUpdateException`, cleanup suppressed, and no partial batch data visible from a fresh connection.

### Tests (write first, must fail)

- [X] T009 [P] [US3] Failure-injecting proxy test support wrapping a real H2 connection/DataSource: configurable throws on `executeBatch`, `rollback`, `close` (extend the `CloseCountingDataSource` pattern) — new `src/test/scala/org/galaxio/gatling/jdbc/db/testsupport/FailingConnectionDataSource.scala`
- [X] T010 [US3] Suppression + no-partial-commit spec: (a) execution fails + rollback fails → Future failure IS the execution exception, rollback exception in `getSuppressed`, **and no partial batch rows visible from a fresh connection** (guards the setAutoCommit-commits-transaction trap); (b) execution fails + statement close fails → same primary/suppressed shape (guards the `Using` half); (c) rollback succeeds → primary unchanged, no suppressed, rollback effective; (d) non-batch path (`executeUpdate`) with failing statement/connection close → primary preserved, close failure suppressed — new `src/test/scala/org/galaxio/gatling/jdbc/db/BatchCleanupSuppressionSpec.scala` (depends on T009)

### Implementation

- [X] T011 [US3] Restructure the batch transaction flow per R6 into a reusable transaction-scope helper in `src/main/scala/org/galaxio/gatling/jdbc/db/JDBCClient.scala`: (1) the primary execution exception is **thrown** inside the `Using.Manager` scope (nested-`Try`-plus-`.flatten` shape removed) so `Using` suppression applies to close failures; (2) rollback failure → `primary.addSuppressed(rollbackEx)`, primary still propagates; (3) failed rollback → `pool.evictConnection(conn)` and **skip** the auto-commit restore (a mid-transaction `setAutoCommit(true)` commits — never allowed on the failure path); today's `DisableAutoCommit` becomes the failure-aware scope helper (restore on success, evict on failed rollback). Helper is shared with US4's discard path. Commit `fix(db): preserve primary batch failure over cleanup failures (#84)`

**Checkpoint**: `sbt "testOnly *BatchCleanupSuppression*"` + full gate green.

---

## Phase 6: User Story 4 — Load-only queries do not exhaust the load generator (Priority: P4)

**Goal**: No-check queries drain without retention inside a plugin-managed read transaction (real streaming on PostgreSQL); `maxRows(n)` cap enforced on **every** path with KO on overflow; checks-present default unchanged (#86, FR-007, R4). Single commit.

**Independent Test**: No-check query over a 1M-row H2 result completes OK retaining no rows; `maxRows(n)` against n+1 rows → KO naming the cap on both the discard and materializing paths; n rows → OK with all rows to checks.

### Tests (write first, must fail)

- [X] T012 [P] [US4] Client-level spec — new `src/test/scala/org/galaxio/gatling/jdbc/db/BoundedRetrievalSpec.scala`: `executeSelectDiscard` drains `SELECT x FROM SYSTEM_RANGE(1, 1000000)` returning the count, retaining no row maps (SC-005 at spec scale, in CI); cap on the discard path: cap exceeded → failure naming cap and overflow; capped materializing overload: cap == size OK, cap exceeded → failure, zero-row result OK; `maxRows = Int.MaxValue` valid (guard math in `Long`, no overflow); duplicate labels rejected on the discard path (`DuplicateColumnLabelException`, no silent drain)
- [X] T013 [P] [US4] Action-level routing spec (existing `JdbcActionSpecSupport`/fixture pattern): no checks → discard path used, OK logged, timing recorded; no checks + `maxRows` → cap still enforced (KO on overflow — cap never silently ignored); checks present → rows delivered exactly as today; checks + `maxRows` overflow → KO with cap message — new `src/test/scala/org/galaxio/gatling/jdbc/actions/QueryRetentionRoutingSpec.scala`
- [X] T014 [P] [US4] Facade coverage: Java `maxRows(int)` passthrough routes correctly (follow `src/test/scala/org/galaxio/gatling/jdbc/javaapi/QueryActionBuilderBranchSpec.scala` pattern) **and** Kotlin usage compiles/runs in the existing Kotlin test tree (`src/test/kotlin/org/galaxio/performance/jdbc/test/cases/KtJdbcActions.kt` or sibling)

### Implementation

- [X] T015 [US4] `JDBCClient`: add `executeSelectDiscard(sql, params, maxRows: Option[Int])(consumer: Try[Long] => U)` — runs inside the R6 transaction-scope helper (per-connection auto-commit off → commit on success; forward-only statement + fetch-size hint) so PostgreSQL actually streams; validates duplicate labels once before draining (reuse US2 check); counts while draining, overflow → failure. Add capped variant of `executeSelect` (new `maxRows: Option[Int]` parameter with default, or overload — existing call shape stays source-compatible); driver-side guard `setLargeMaxRows(cap + 1)` computed in `Long`, hint skipped where unsupported, correctness always from counting — `src/main/scala/org/galaxio/gatling/jdbc/db/JDBCClient.scala` (depends on T011's helper)
- [X] T016 [US4] DSL + routing: `QueryActionBuilder.maxRows(n: Int)` (require n > 0; new case-class field with default `None` — constitution I's additive path, source-compatible; synthetic `apply`/`copy` shift documented in api-surface + release notes) in `src/main/scala/org/galaxio/gatling/jdbc/actions/actions.scala`; `DBQueryAction` routes `checks.isEmpty` → `executeSelectDiscard(…, maxRows)`, else capped/uncapped select — cap reaches **both** branches — `src/main/scala/org/galaxio/gatling/jdbc/actions/DBQueryAction.scala` (depends on T015)
- [X] T017 [US4] Java facade passthrough `maxRows(int)` — `src/main/java/org/galaxio/gatling/javaapi/actions/QueryActionBuilder.java` (depends on T016). Commit all of US4 as `feat(actions): bounded result retrieval — discard path and maxRows cap (#86)` (body: additive API + no default change)

**Checkpoint**: `sbt "testOnly *BoundedRetrieval* *QueryRetentionRouting*"` + full gate green.

---

## Phase 7: User Story 5 — Large-object values remain usable in checks (Priority: P5)

**Goal**: Blob/Clob/NClob/SQLXML/Array detached to `Array[Byte]`/`String`/`Vector[Any]` while the ResultSet is open, locators freed even when detachment fails; readable after completion (#87, FR-006, R3).

**Independent Test**: Insert BLOB/CLOB rows on H2, query, read full content in a check after the operation completes — no invalid-locator error; null/empty cases per data-model table; XML asserted on PostgreSQL.

### Tests (write first, must fail)

- [X] T018 [P] [US5] H2 detachment spec: BLOB → `Array[Byte]` content+length readable post-completion; CLOB → `String`; NCLOB → `String`; `ARRAY` → `Vector[Any]` including an array-of-LOB element case (recursive detachment); null column → `null`; empty LOB → empty array/string — new `src/test/scala/org/galaxio/gatling/jdbc/db/LobDetachmentSpec.scala`
- [X] T019 [P] [US5] PostgreSQL coverage: `bytea`/`text` round-trip through checks post-completion **and** `xml` column → `String` (H2's SQLXML support is weak — PG carries the XML assertion) — extend `src/test/scala/org/galaxio/gatling/jdbc/db/PostgreSQLIntegrationSpec.scala`

### Implementation

- [X] T020 [US5] Value detachment in `record` per [contracts/result-mapping.md](contracts/result-mapping.md) table: match on `Blob`/`Clob`/`NClob`/`SQLXML`/`Array` → copy, then `free()` in a `finally`-equivalent path (free failure → `addSuppressed` on the primary copy failure, never replacing it); recursive mapping for array elements; length > `Int.MaxValue` → clear exception; all other types untouched — `src/main/scala/org/galaxio/gatling/jdbc/db/package.scala` (after US2 lands — same file, sequential). Commit `fix(db): detach LOB values while the ResultSet is open (#87)` (body: behavior-change note)

**Checkpoint**: `sbt "testOnly *LobDetachment*"` + PG slice + full gate green.

---

## Phase 8: User Story 6 — Dynamic table and column names are validated (Priority: P6)

**Goal**: Table names (feeder-reachable `Expression[String]`) and static column names validated against [contracts/identifier-grammar.md](contracts/identifier-grammar.md) before SQL assembly; invalid → per-request KO, nothing sent (#124, FR-008, R7). Single commit.

**Independent Test**: Malicious **table** feeder value (`users; DROP TABLE t`) → request KO with validation message, target table intact, no statement executed; invalid static column name → KO; plain/qualified/quoted identifiers keep working.

### Tests (write first, must fail)

- [X] T021 [P] [US6] Grammar unit spec: full accept/reject table from the contract — accepts: plain, qualified ≤3 segments, ANSI-quoted with `""` escape, backtick-quoted, **128-char unquoted boundary**; rejects: whitespace/`;`/`--`/unbalanced quotes/empty quoted/4 segments/129-char unquoted/NUL/**`{` or `}` inside quoted segments** (placeholder-collision rule) — new `src/test/scala/org/galaxio/gatling/jdbc/db/SqlIdentifierSpec.scala`
- [X] T022 [P] [US6] End-to-end action spec on H2: insert + batch(insert/update) with a malicious **table-name feeder** value → KO via crash path with value quoted in message, table row-count unchanged; invalid **static column name** in `Columns(...)`/SET keys → KO before any SQL; valid quoted identifier executes; `where(...)` fragment stays un-validated (contract) — new `src/test/scala/org/galaxio/gatling/jdbc/actions/IdentifierValidationSpec.scala`

### Implementation

- [X] T023 [P] [US6] `SqlIdentifier` validator object + `InvalidSqlIdentifierException` implementing the grammar (segment split respecting quotes, per-form check, brace exclusion in quoted bodies, message quotes rejected value + accepted forms) — new `src/main/scala/org/galaxio/gatling/jdbc/db/SqlIdentifier.scala`
- [X] T024 [US6] Wire validation into resolve paths: session-resolved table names + static column names (insert columns, update SET keys) validated inside the `Validation` for-comprehensions → failure flows to existing `crashOnFailure` KO — `src/main/scala/org/galaxio/gatling/jdbc/actions/DBInsertAction.scala` and `src/main/scala/org/galaxio/gatling/jdbc/actions/DBBatchAction.scala` (depends on T023). No `Expression`-typed columns API (compat-sensitive, out of scope per R7). Commit `fix(actions): validate SQL identifiers before statement assembly (#124)` (body: behavior-change note)

**Checkpoint**: `sbt "testOnly *SqlIdentifier* *IdentifierValidation*"` + full gate green.

---

## Phase 9: Polish & Cross-Cutting

- [X] T025 Full verification sweep per [quickstart.md](quickstart.md): format gate, `sbt test`, `DebugTest` simulation, PostgreSQL slice — all green, zero regressions (SC-007)
- [X] T026 [P] Docs PR (separate from issue commits per repo rule): README — (a) **Transaction control** section: why `autoCommit=false` pool config is rejected (pool checkin rolls back dirty connections; transactions are per-connection and connections are not pinned to virtual users, so a later `rawSql("COMMIT")` can never reach an earlier action's transaction), and the two supported patterns — `batch` DSL for accumulate-then-commit (one plugin-managed transaction per request) and a single-action `rawSql` block (`BEGIN; …; COMMIT`); (b) `maxRows` usage incl. cap-applies-on-all-paths semantics; (c) identifier quoting policy incl. the `{`/`}` exclusion; (d) behavior-change table from [contracts/api-surface.md](contracts/api-surface.md) for v1.4.0 release notes; (e) result-key case rule per engine + LOB/Array/XML representation table from [contracts/result-mapping.md](contracts/result-mapping.md)
- [X] T027 Delivery-contract audit before merge: every fix PR carries milestone `v1.4.0 …` + `Closes #NNN` (`scripts/check-linkage.sh` active-milestone audit) **and** — for `db/`/protocol/action-execution changes — a constitution-III statement in the PR body covering pool sizing, transaction boundaries, timeout handling, error propagation, and thread behavior

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 (T001) first; Phase 2 empty; stories then run in priority order US1 → US2 → US3 → US4 → US5 → US6; Phase 9 last.

### Cross-story file constraints (why full parallelism is limited)

- `db/JDBCClient.scala`: touched by US1 (T003), US3 (T011), US4 (T015) → strictly sequential; **US4 additionally depends on US3's transaction-scope helper** (R6 → R4 reuse), so US3 must land before US4 regardless of file ordering.
- `db/package.scala`: touched by US2 (T007, T008) then US5 (T020) → US5 implementation waits for US2; US4's discard path reuses US2's duplicate-label check (T008 → T015).
- `PostgreSQLIntegrationSpec.scala`: extended by US2 (T005) and US5 (T019) → sequential.
- US6 is file-disjoint from everything (`SqlIdentifier.scala`, `DBInsertAction.scala`, `DBBatchAction.scala`) → whole phase can run in parallel with US2–US5 if staffed.

### Within stories

- Tests before implementation, failing first (constitution II); test + fix land in the same issue commit.
- T009 → T010; T011 → T015 → T016 → T017; T023 → T024.

## Parallel Examples

```text
US2 test fan-out:   T004 (ResultSetLabelSpec) ∥ T005 (PG assertions) ∥ T006 (DuplicateColumnLabelSpec)
US4 test fan-out:   T012 (client) ∥ T013 (action routing) ∥ T014 (facade Java+Kotlin)
US6 fan-out:        T021 (grammar spec) ∥ T022 (e2e spec) ∥ T023 (validator impl)
Cross-story:        US6 phase ∥ US2–US5 (disjoint files)
```

## Implementation Strategy

- **MVP = US1 alone**: kills the worst silent failure (OK-without-persistence) in one small commit; independently shippable.
- **Incremental**: land stories in priority order; each checkpoint = full gate green + story spec green; stop-and-ship possible after any checkpoint.
- **Commit map**: 7 commits — #88, #122, #123, #84, #86, #87, #124 — matching FR-001…FR-008; plus separate docs PR (T026).
