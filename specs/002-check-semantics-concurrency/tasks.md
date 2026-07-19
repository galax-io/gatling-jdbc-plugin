# Tasks: Check Semantics & Concurrency Correctness (Milestone v1.3.0)

**Input**: Design documents from `/specs/002-check-semantics-concurrency/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/action-check-batch-contract.md](contracts/action-check-batch-contract.md), [quickstart.md](quickstart.md)

**Tests**: REQUIRED — constitution II (Test-First With Real Databases) and spec SC-001 mandate an H2-backed regression test per defect, written first, failing for the right reason, then fixed **in the same commit** (constitution II: test + behavior change land together).

**Organization**: Grouped by user story from spec.md. US1 = hang fixes (#77, #78), US2 = check composition (#79, #80), US3 = batch ordering (#82). Story-to-issue mapping is 1 issue = 1 Conventional Commit (`fix(...): … (#NN)`), each green on its own (`sbt scalafmtCheckAll scalafmtSbtCheck compile test`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

## Path Conventions

Single existing sbt project — paths per plan.md Project Structure: production code under `src/main/{scala,java}`, tests under `src/test/scala` alongside existing specs. Reuse `src/test/scala/org/galaxio/gatling/jdbc/actions/JdbcActionSpecSupport.scala` (H2 `TestContext`, capture helpers) — no new test infrastructure.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm a green baseline so every subsequent commit's gate result is attributable to that commit alone.

- [x] T001 Verify baseline gate is green before any change: run `sbt scalafmtCheckAll scalafmtSbtCheck compile test` and `sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"` at repo root; record any pre-existing failure (none expected) before proceeding

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: None required — no new infrastructure, dependencies, or shared models. Existing test support (`JdbcActionSpecSupport`, H2 in-memory, ScalaTest) already covers all five regression specs, and the five fixes touch disjoint code paths (see research.md scope confirmation).

**Checkpoint**: Phase 1 green = user story implementation can begin; US1/US2/US3 are mutually independent.

---

## Phase 3: User Story 1 — Checks fail loudly instead of hanging the simulation (Priority: P1) 🎯 MVP

**Goal**: A missing `requestName` session attribute (#77) or a throwing check predicate (#78) produces exactly one KO, one failed session, one stats entry, one `next` call — never a hung virtual user. Restores contracts 1–2 of [contracts/action-check-batch-contract.md](contracts/action-check-batch-contract.md).

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.actions.RequestNameCrashSpec"` and `sbt "testOnly org.galaxio.gatling.jdbc.actions.ThrowingCheckSpec"` pass; both specs hang/fail on the unfixed code.

### Tests + Implementation for User Story 1 (test written first, failing, then fix — same commit per issue)

- [x] T002 [P] [US1] Write failing regression spec `src/test/scala/org/galaxio/gatling/jdbc/actions/RequestNameCrashSpec.scala`: for **each of the 5 action types** (`DBQueryAction`, `DBInsertAction`, `DBBatchAction` — whose expression field is named `batchName`, `DBCallAction`, `DBRawQueryAction`) build the action via `JdbcActionSpecSupport.buildRealTestContext` with the name expression referencing a missing session attribute (real Gatling EL, e.g. `"#{missing}".el[String]`), execute against H2, assert within a bounded wait: exactly one KO/`logRequestCrash` stats entry recorded under the action's stable Gatling `name` fallback, session marked failed, `next` invoked exactly once (use a latch/capture action — spec must FAIL by timeout on current code because `next` is never called); add one type-mismatch case (spec.md Edge Case 1): session attribute present but non-String (e.g. an `Int`) on at least the query action, asserting the same one-KO/one-`next` contract
- [x] T003 [US1] Fix `crashOnFailure` in `src/main/scala/org/galaxio/gatling/jdbc/actions/ActionBase.scala:28-34`: resolve `requestName(session)` once via `.toOption.getOrElse(name)` — `name` is the action's stable Gatling action name, available through the `ChainableAction` self-type (e.g. `genName("jdbcQueryAction")` per `DBQueryAction.scala:26`) — keeping `logRequestCrash` + `executeNext(markAsFailed, KO)` unconditional, no `.map` that silently no-ops (research.md decision); run `sbt scalafmtAll scalafmtSbt` then full gate; commit test T002 + this fix together as `fix(actions): emit KO instead of hanging VU on unresolved requestName EL (#77)`
- [x] T004 [P] [US1] Write failing regression spec `src/test/scala/org/galaxio/gatling/jdbc/actions/ThrowingCheckSpec.scala`: H2-backed `DBQueryAction` with a successful `SELECT` plus a `JdbcCheckSupport.simpleCheck` predicate that throws `RuntimeException`; assert exactly one KO reported through the check-failure path (`Some("Check ERROR")` or exception message), session failed, one stats entry, `next` called exactly once (must FAIL by timeout on current code — exception escapes into the `Future` and `next` never fires)
- [x] T005 [US1] Guard the check evaluation in `src/main/scala/org/galaxio/gatling/jdbc/actions/DBQueryAction.scala:36-52`: wrap `Check.check(...)` + match in a `NonFatal` catch routing to the same KO/`executeNext` path as a normal check failure (per research.md — guard at the single call site, NOT inside `JdbcCheckSupport` and NOT inside `JDBCClient.executeSelect`); format, full gate, commit test T004 + fix as `fix(actions): report KO instead of hanging VU when a JDBC check throws (#78)`

**Checkpoint**: US1 fully functional — no input can leave a VU without a `next` call; MVP deliverable.

---

## Phase 4: User Story 2 — Chained and branched checks behave as declared (Priority: P1)

**Goal**: Scala `.check(a).check(b)` runs both checks (#79); Java `QueryActionBuilder` branches derived from one base stay independent (#80). Restores contract 3.

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.actions.QueryActionBuilderCheckChainSpec"` and `sbt "testOnly org.galaxio.gatling.jdbc.javaapi.QueryActionBuilderBranchSpec"` pass; each fails on unfixed code.

### Tests + Implementation for User Story 2

- [x] T006 [P] [US2] Write failing regression spec `src/test/scala/org/galaxio/gatling/jdbc/actions/QueryActionBuilderCheckChainSpec.scala`: (a) unit-level — `jdbc("q").queryP(...)...check(a).check(b)` builder exposes `checks` containing BOTH `a` and `b` in declaration order; (b) behavior-level mutation test over H2 — chain a first check that fails with a distinctive message and a second that passes, execute, assert the FIRST check's failure is reported (proves it wasn't dropped); also chain three checks with two failing to cover the multi-failure edge case from spec.md (must FAIL on current code: `copy(checks = newChecks)` drops all but the last call)
- [x] T007 [US2] Fix `check` in `src/main/scala/org/galaxio/gatling/jdbc/actions/actions.scala:49` to `this.copy(checks = checks ++ newChecks)` and add a scaladoc line stating checks execute in declaration order (spec FR-005); format, full gate, commit test T006 + fix as `fix(actions): append chained query checks instead of replacing them (#79)`
- [x] T008 [P] [US2] Write failing regression spec `src/test/scala/org/galaxio/gatling/jdbc/javaapi/QueryActionBuilderBranchSpec.scala` (ScalaTest over the Java class, same pattern as existing `UtilsSpec.scala`): create one base `org.galaxio.gatling.javaapi.actions.QueryActionBuilder`, derive branch A via `.check(<non-empty-result check>)` and branch B via `.check(<empty-result check>)`, execute both against H2 tables prepared empty/non-empty; assert each branch reports only its own check outcome regardless of build order, assert `base` itself remains check-free after both derivations (covers the base-reuse edge case from spec.md), and assert branch A `ne` branch B (distinct instances) (must FAIL on current code: `.check()` reassigns `this.wrapped` and returns `this`)
- [x] T009 [US2] Fix `src/main/java/org/galaxio/gatling/javaapi/actions/QueryActionBuilder.java`: make `wrapped` `private final`, change `check(List<Object>)` to `return new QueryActionBuilder(wrapped.check(...))` (copy-on-write, matching sibling builders like `DBCallActionBuilder` — no signature change); format, full gate, commit test T008 + fix as `fix(javaapi): stop QueryActionBuilder.check mutating shared builder branches (#80)`

**Checkpoint**: US1 + US2 independent and green — declared assertions always execute, branches never contaminate.

---

## Phase 5: User Story 3 — Batch operations preserve declared execution order (Priority: P2)

**Goal**: `JDBCClient.batch` executes distinct SQL in declared order, grouping only contiguous identical SQL (#82). Restores contract 4.

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.db.BatchOrderSpec"` passes; fails (wrong final DB state / wrong count order) on unfixed code.

### Tests + Implementation for User Story 3

- [x] T010 [P] [US3] Write failing regression spec `src/test/scala/org/galaxio/gatling/jdbc/db/BatchOrderSpec.scala` (H2, same fixture style as existing `BatchPreparedStatementSpec.scala`): (a) submit `insert A -> update all rows -> insert B` as `Seq[SqlWithParam]` with distinct SQL texts chosen so `groupBy`+HashMap ordering breaks it, assert final row state shows A affected by the update and B not, and per-statement result counts arrive in declared order; (b) non-contiguous same-SQL case `[insert X, update all, insert Y via same INSERT sql]` — assert Y is NOT merged into X's pre-update batch group; (c) contiguous-efficiency case — 3 consecutive identical inserts still produce correct counts (grouping allowed, order-observable behavior unchanged)
- [x] T011 [US3] Fix batch grouping in `src/main/scala/org/galaxio/gatling/jdbc/db/JDBCClient.scala:160-185`: replace `queries.groupBy(_.sql).toSeq` with order-preserving contiguous-run chunking (walk `queries` in order, merge only adjacent elements with identical SQL into one `addBatch`/`executeBatch` group, execute groups in first-element order — per research.md decision); do NOT touch `withConnectionForBatch`, commit/rollback `.transform`, or `queryTimeoutSeconds` propagation (constitution III — existing `BatchPreparedStatementSpec`, `BatchQueryTimeoutSpec`, `ResourceReleaseOnSyncThrowSpec` must stay green unmodified); format, full gate, commit test T010 + fix as `fix(db): preserve declared SQL order in batch execution (#82)`

**Checkpoint**: All three user stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Whole-feature validation, compatibility proof, delivery mechanics.

- [x] T012 Run full [quickstart.md](quickstart.md) validation: `sbt scalafmtCheckAll scalafmtSbtCheck compile test` + `sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"`; confirm all 5 new specs pass, all pre-existing specs pass **unmodified**, and existing Java/Kotlin usage sources under `src/test/java` + `src/test/kotlin` compiled without edits (SC-005 signature-compatibility proof)
- [x] T013 Mark spec artifacts complete: update `specs/002-check-semantics-concurrency/tasks.md` checkboxes + add as-built notes for any deviation from research.md decisions; commit as `docs(speckit): mark 002-check-semantics-concurrency tasks complete`
- [x] T014 Open PR(s) for the five fix commits (stacked or sequential per AGENTS.md "1 concern per PR"): each PR body carries `Closes #77` / `#78` / `#79` / `#80` / `#82` for the commits it lands, assign every PR to milestone **v1.3.0 — Runtime correctness** (galax-io milestone 6); the PR carrying #82 (touches `db/`) MUST state the constitution-III considerations in its body — transaction atomicity, `queryTimeoutSeconds` propagation, error propagation, and resource release all unchanged by the reordering fix (text available in plan.md Constitution Check III); then verify linkage with `bash scripts/check-linkage.sh --pr <N>` for each PR before merge

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: none — run immediately
- **Phase 2 (Foundational)**: empty — Phase 1 green unblocks all stories
- **Phase 3/4/5 (US1/US2/US3)**: each depends only on Phase 1; **no cross-story dependencies** — the five fixes touch disjoint files (`ActionBase.scala` + `DBQueryAction.scala` | `actions.scala` + `QueryActionBuilder.java` | `JDBCClient.scala`)
- **Phase 6 (Polish)**: depends on all story phases complete

### Within Each User Story

- Test task FIRST (must fail for the right reason — a hang-shaped failure is asserted via bounded wait/timeout, not an actual infinite hang), then fix task; test + fix land in the SAME commit (constitution II), one commit per issue (constitution IV)
- T003 depends on T002; T005 depends on T004; T007 depends on T006; T009 depends on T008; T011 depends on T010
- Within US2: T007 (#79, Scala) before T009 (#80, Java) — not a hard dependency (branch test uses a check-free base so replace-vs-append is indistinguishable), but Scala-side append semantics landing first keeps the Java wrapper's delegated behavior final when its test is written

### Parallel Opportunities

- All five test-authoring tasks (T002, T004, T006, T008, T010) are [P] — five different new files, no shared state
- Fix commits are inherently sequential (each must be green on its own gate — serialize commits), but the code edits themselves touch disjoint files
- US1, US2, US3 can be worked by different people concurrently; commit order across stories is free

---

## Parallel Example: kick off all regression specs at once

```bash
# All five failing specs can be authored in parallel (different files):
Task: "Write RequestNameCrashSpec covering all 5 action types"        # T002
Task: "Write ThrowingCheckSpec for throwing simpleCheck"              # T004
Task: "Write QueryActionBuilderCheckChainSpec for chained checks"     # T006
Task: "Write QueryActionBuilderBranchSpec for Java branch isolation"  # T008
Task: "Write BatchOrderSpec for declared-order batch execution"       # T010
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 baseline gate
2. T002→T003 (#77), T004→T005 (#78)
3. **STOP and VALIDATE**: no input can hang a VU — the worst defect class is closed; ship as MVP if needed

### Incremental Delivery

1. US1 → validate → the "hangs" class is gone (2 commits: #77, #78)
2. US2 → validate → declared checks always execute (2 commits: #79, #80)
3. US3 → validate → batch order trustworthy (1 commit: #82)
4. Polish → quickstart gate + PRs with milestone linkage → milestone v1.3.0 tag-ready once all 5 issues close

Every commit is independently green, so delivery can pause after any issue without leaving the branch red.

---

## Notes

- 1 issue = 1 Conventional Commit (`fix(scope): subject (#NN)`) — subjects above are prescriptive; they drive the v1.3.0 changelog via git-cliff
- Pre-commit hook formats staged sources automatically (`.githooks/pre-commit`); run `bash scripts/install-hooks.sh` once per clone if not yet enabled
- No public signature changes anywhere (spec FR-008) — if a fix appears to need one, STOP and re-plan
- H2 only; PostgreSQL/Testcontainers not needed (no driver-specific behavior touched)
- New specs must assert via bounded waits (e.g. `eventually` / latch with timeout), never unbounded `Await` — the pre-fix behavior under test IS a hang

## As-Built Notes (2026-07-19)

Fix commits: `5c37099` (#77), `294005f` (#78), `8acc2d0` (#79), `afd0d0a` (#80), `91d226d` (#82). All five
regression specs were written first and observed failing for the intended reason before each fix landed.

Deviations / discoveries relative to the planned tasks:

- **T001**: Docker was unavailable locally, so the two Testcontainers-based suites
  (`BatchQueryTimeoutSpec`, `PostgreSQLIntegrationSpec`) abort in this environment — a pre-existing
  environmental limitation, not a regression; every H2-backed suite was green at baseline (69 tests) and
  stays green throughout (now 85). CI (with Docker) covers the aborted pair.
- **T002 (edge case)**: Gatling's String EL caster stringifies non-String session attributes, so the
  "attribute present but not a String" case *resolves successfully* ("42") rather than crashing — the test
  asserts the actual contract (exactly one `next`, no hang, stats entry under the resolved name "42")
  instead of a KO that cannot occur.
- **T003 (scope addition)**: the stable-name fallback exposed that all five actions declared
  `override def name = genName(...)` — regenerating the NameGen counter on every access, so no stable name
  existed to fall back to. Changed to construction-time `val` in all five actions (NameGen's intended use;
  `val` overrides `def`, no signature change). `DBRawQueryAction`'s copy-pasted `"jdbcInsertAction"` label
  was corrected to `"jdbcRawQueryAction"` since #77 makes the name user-visible on the crash path.
- **Test infrastructure**: `GatlingTestSupport.makeScenarioContext` gained an optional `statsEngine`
  parameter (default: the existing no-op) plus a `RecordingStatsEngine`, and `JdbcActionSpecSupport`
  forwards it; `CaptureAction` now counts invocations. Test-only — existing specs unchanged.
- **T012**: full gate + `DebugTest` green post-implementation; Java usage sources under `src/test/java`
  compiled unmodified. Kotlin sources under `src/test/kotlin` are usage examples not wired into the sbt
  build (no Kotlin plugin), so their compatibility is by construction of the unchanged `javaapi` facade
  signatures.
