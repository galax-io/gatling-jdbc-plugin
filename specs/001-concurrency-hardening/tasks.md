# Tasks: Statement Concurrency & Resource-Safety Hardening

**Input**: Design documents from `/specs/001-concurrency-hardening/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/jdbc-client-behavior.md, quickstart.md

**Tests**: Tests ARE the deliverable of this feature (FR-008; issues #83/#100/#120/#121 each prescribe an acceptance test). Unlike classic TDD, these regression tests are expected to PASS against current `main` — they pin behavior already fixed by PR #59. A failing test means a residual defect (contingency path, only realistic for US3/#83).

**Organization**: One user story = one GitHub issue = one semantic commit, green on its own (`sbt scalafmtCheckAll scalafmtSbtCheck compile test`) — Constitution IV.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1=#120, US2=#121, US3=#83, US4=#100

## Path Conventions

Single sbt project: `src/main/scala/...`, `src/test/scala/...` at repository root (per plan.md).

---

## Phase 1: Setup

**Purpose**: Land spec artifacts first (Constitution IV), confirm green baseline.

- [x] T001 Commit spec artifacts: `git add specs/001-concurrency-hardening .specify/feature.json && git commit -m "docs(speckit): add 001-concurrency-hardening spec/plan/tasks"`. Do NOT include CLAUDE.md (AGENTS.md: never commit CLAUDE.md unless user explicitly asks).
- [x] T002 [P] Verify baseline green on branch `001-concurrency-hardening`: `sbt scalafmtCheckAll scalafmtSbtCheck compile test`
- [x] T003 [P] Verify Docker available for Testcontainers (`docker info`); if unavailable, note that PostgreSQL specs (US2/US3 parts) must run in CI

---

## Phase 2: Foundational

**Purpose**: Shared test instrumentation used by US1, US2, US4. No production code.

**⚠️ CRITICAL**: US1/US2/U4 test tasks import these helpers; write them first. They are committed together with the first story commit that uses them (helpers alone don't map to an issue).

- [x] T004 Create concurrency-recording statement proxy in src/test/scala/org/galaxio/gatling/jdbc/db/testsupport/RecordingStatementProxy.scala — JDK `java.lang.reflect.Proxy` factory wrapping a real H2 `PreparedStatement`/`CallableStatement`; `InvocationHandler` tracks: current/max concurrent entries into `set*`/`setObject`/`registerOutParameter` (AtomicInteger enter/exit), per-index invocation counts, bound values per index; delegates every call to the real statement. Serves US1+US2 (contract G1.1/G1.3/G2.1).
- [x] T005 Create close-counting datasource in src/test/scala/org/galaxio/gatling/jdbc/db/testsupport/CloseCountingDataSource.scala — test-only `HikariDataSource` subclass; `getConnection` returns JDK-proxy `Connection` that (a) counts `close()` per connection, (b) wraps created `Statement`/`PreparedStatement`/`CallableStatement` in close-counting proxies, (c) supports injecting a non-fatal exception from one resource's `close()`. Serves US4 (contract G4.1/G4.2). No production seam — research.md decision.

**Checkpoint**: helpers compile; user stories can start.

---

## Phase 3: User Story 1 — Correct parameter binding for parameterized queries under load (Priority: P1) 🎯 MVP — issue #120

**Goal**: Prove PreparedStatement parameter binding is serialized (max concurrent == 1) and every position gets exactly its declared value exactly once; pin with regression tests (contract G1.*, FR-001).

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.db.StatementParamsConcurrencySpec"` green + H2/PG round-trip values 100% correct.

### Tests for User Story 1

- [x] T006 [US1] Create src/test/scala/org/galaxio/gatling/jdbc/db/StatementParamsConcurrencySpec.scala (prepared-statement cases, using T004 proxy over real H2 connection): multi-param SQL (all `ParamVal` kinds incl. UUID, null, boolean, date) → assert max concurrent setter entry == 1; each JDBC index bound exactly once; bound values equal declared values; duplicate placeholder (`{a},{b},{a}`) binds every mapped index exactly once; zero-param and single-param edge cases prepare successfully; missing binding for a placeholder → operation fails with original exception, statement never executed (G1.4)
- [x] T007 [P] [US1] Extend src/test/scala/org/galaxio/gatling/jdbc/db/PostgreSQLIntegrationSpec.scala with concurrent value-correctness case: N≥50 concurrent `executeUpdate` inserts with distinct known multi-param rows through one `JDBCClient` → read back via `executeSelect`, assert 100% of rows contain exactly the declared values (SC-002)

### Commit for User Story 1

- [x] T008 [US1] Format + gate + commit: `sbt scalafmtAll scalafmtSbt && sbt scalafmtCheckAll scalafmtSbtCheck compile test`, then `git add src/test && git commit -m "test(db): prove serialized PreparedStatement param binding (#120)"` (includes T004 helper; T005 if not yet committed stays unstaged)

**Checkpoint**: US1 independently green; #120 acceptance satisfied.

---

## Phase 4: User Story 2 — Reliable stored-procedure IN/OUT registration under load (Priority: P2) — issue #121

**Goal**: Prove CallableStatement IN binding + OUT registration are serialized, each position exactly once; correct OUT values from a real stored procedure under load (contract G2.*, FR-002).

**Independent Test**: callable cases of `StatementParamsConcurrencySpec` + stored-proc case of `PostgreSQLIntegrationSpec` green.

### Tests for User Story 2

- [x] T009 [US2] Add callable-statement cases to src/test/scala/org/galaxio/gatling/jdbc/db/StatementParamsConcurrencySpec.scala (T004 proxy over real H2 `CallableStatement` via `CREATE ALIAS`): mixed IN+OUT call → assert max concurrent setter/`registerOutParameter` entry == 1; every IN index bound exactly once; every OUT index registered exactly once; only-IN and only-OUT calls hold the same invariants; missing OUT placeholder → `IllegalArgumentException` naming missing params (G2.3, existing behavior)
- [x] T010 [P] [US2] Extend src/test/scala/org/galaxio/gatling/jdbc/db/PostgreSQLIntegrationSpec.scala with stored-procedure-under-load case: PostgreSQL function with IN + OUT params (`CREATE FUNCTION`), N≥50 concurrent `call` invocations with distinct inputs → every call returns correct OUT values for its inputs (SC-002)
  > **As built**: implemented on H2 (`CREATE ALIAS` + `? = CALL`, 50 concurrent `client.call`s in StatementParamsConcurrencySpec) instead of PostgreSQL. The pgjdbc OUT-parameter path requires the JDBC `{call ...}` escape syntax, but literal braces are consumed by the plugin's named-placeholder interpolator — a real product limitation worth its own issue; H2 provides the real-database OUT path without it.

### Commit for User Story 2

- [x] T011 [US2] Format + gate + commit: `sbt scalafmtAll scalafmtSbt && sbt scalafmtCheckAll scalafmtSbtCheck compile test`, then `git commit -m "test(db): prove serialized CallableStatement IN/OUT registration (#121)"`

**Checkpoint**: US1+US2 green independently.

---

## Phase 5: User Story 3 — Batch operations honor the configured query timeout (Priority: P3) — issue #83

**Goal**: Prove a slow batch aborts as KO within configured timeout + margin; fast/no-timeout batches unchanged (contract G3.*, FR-003/FR-004). Only story with a realistic production-fix contingency.

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.db.BatchQueryTimeoutSpec"` green.

### Tests for User Story 3

- [x] T012 [US3] Create src/test/scala/org/galaxio/gatling/jdbc/db/BatchQueryTimeoutSpec.scala (PostgreSQL Testcontainers, pattern from PostgreSQLIntegrationSpec:110-134): (a) `JDBCClient` with `queryTimeout = 1.second`, batch `INSERT ... SELECT ... , pg_sleep(10)` → `batch` completes as `Failure` (KO) within ~1s + fixed margin (assert wall-clock < 5s, not 10s), transaction rolled back, connection returned to pool; (b) same client, fast batch → succeeds with correct counts; (c) client with `queryTimeout = None`, moderately slow batch → completes without implicit timeout (G3.3)
- [x] T013 [US3] CONTINGENCY (only if T012(a) shows timeout not aborting): fix batch execution in src/main/scala/org/galaxio/gatling/jdbc/db/JDBCClient.scala `batch` method only, preserving G3.3/G3.4 and rollback semantics; re-run T012
  > **Not needed**: T012(a) is green — the driver aborts the slow batch at the configured timeout (Failure in <5s vs a 10s sleep), rollback and pool release verified. No production change.

### Commit for User Story 3

- [x] T014 [US3] Format + gate + commit: `test(db): prove batch honors configured queryTimeout (#83)` — or `fix(db): enforce queryTimeout on batch statements (#83)` if T013 executed

**Checkpoint**: US1-US3 green independently.

---

## Phase 6: User Story 4 — Resources always released when an operation fails immediately (Priority: P4) — issue #100

**Goal**: Prove exactly-once release per resource on synchronous failure, original exception preserved (non-fatal cleanup failure suppressed), no leak under soak (contract G4.* as narrowed after adversarial review, FR-005/FR-006).

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.db.ResourceReleaseOnSyncThrowSpec"` green.

### Tests for User Story 4

- [x] T015 [US4] Create src/test/scala/org/galaxio/gatling/jdbc/db/ResourceReleaseOnSyncThrowSpec.scala using T005 `CloseCountingDataSource` over H2: (a) sync-throw path — `executeSelect` with missing param binding (throws inside `Using` block before execution) → `Failure` carries original exception; every acquired resource's `close()` invoked exactly once; (b) combined-failure — operation fails AND one proxy resource's `close()` throws non-fatal exception → original exception is primary, close failure attached as suppressed (G4.2); (c) soak loop — ≥100 iterations of failing ops → per-iteration close counts stay exactly-once, `HikariPoolMXBean.getActiveConnections == 0` at end (SC-004); (d) success-path control — close counts exactly-once on success too (no regression)

### Commit for User Story 4

- [x] T016 [US4] Format + gate + commit: `test(db): prove exactly-once resource release on synchronous failure (#100)` (includes T005 helper)

**Checkpoint**: all four stories green independently.

---

## Phase 7: Polish & Delivery

**Purpose**: Whole-suite verification, milestone-linked delivery.

- [x] T017 [P] Run example simulation regression guard: `sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"` (SC-005)
- [x] T018 [P] Full gate + coverage: `sbt scalafmtCheckAll scalafmtSbtCheck compile test` and `sbt coverage test coverageReport coverageOff`
- [x] T019 Push branch and open PR to `main` titled `test(db): concurrency & resource-safety regression coverage for v1.2.0`, body with `Closes #83`, `Closes #100`, `Closes #120`, `Closes #121`, assigned to milestone `v1.2.0 — Connection-pool deadlock & concurrency hardening` (milestone 12); PR text states pool/timeout/thread analysis per Constitution III
- [x] T020 Validate linkage: `scripts/check-linkage.sh --pr <N>` passes (milestone + Closes + issues in same milestone)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none — start immediately
- **Foundational (Phase 2)**: after T001 (spec-first commit ordering); T004 blocks US1/US2 tests, T005 blocks US4 tests
- **US1 (Phase 3)**: needs T004. **US2 (Phase 4)**: needs T004; T009 shares StatementParamsConcurrencySpec.scala with T006 → do T006 first (same file). **US3 (Phase 5)**: no helper deps — can run any time after Phase 1. **US4 (Phase 6)**: needs T005.
- **Commit order**: T008 → T011 → T014 → T016 (each `1 issue = 1 commit`, sequential, each green). Stories' TEST AUTHORING can proceed in parallel; commits serialize.
- **Polish (Phase 7)**: after all story commits.

### User Story Dependencies

- US1: independent (T004 helper only)
- US2: file-level dependency on US1's T006 (same spec file); logically independent
- US3: fully independent — earliest candidate for parallel work
- US4: independent (T005 helper only)

### Parallel Opportunities

- T002 ∥ T003 (Phase 1)
- T004 ∥ T005 (different files)
- T007 ∥ T006 (different files); T010 ∥ T009 (different files)
- US3 (T012) ∥ US1/US2/US4 test authoring — different files throughout
- T017 ∥ T018

---

## Parallel Example: US1 + US3 together

```bash
# Different files, no shared state:
Task A: "T006 prepared cases in StatementParamsConcurrencySpec.scala"
Task B: "T012 BatchQueryTimeoutSpec.scala on PostgreSQL Testcontainers"
```

---

## Implementation Strategy

### MVP First (US1 = #120)

1. Phase 1 (T001 spec commit, T002/T003 baseline)
2. T004 helper → US1 tests (T006, T007) → T008 commit
3. **STOP & VALIDATE**: `testOnly ...StatementParamsConcurrencySpec` + gate → #120 closable

### Incremental Delivery

Each subsequent story = one green commit closing one issue: US2 (#121) → US3 (#83) → US4 (#100). Any prefix of this sequence is shippable; milestone v1.2.0 becomes tag-ready only when all four land (check-linkage `--for-tag` gate).

### Notes

- Tests pin already-fixed behavior — expected green immediately; a red test = real finding (esp. T012a).
- Instrumentation (proxies) lives in `testsupport/` only; zero production changes unless T013 contingency fires.
- Scalafmt before every commit (pre-commit hook or `sbt scalafmtAll scalafmtSbt`).
