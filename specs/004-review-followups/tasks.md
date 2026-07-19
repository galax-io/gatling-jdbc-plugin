# Tasks: Post-Review Follow-Ups from the v1.3.0 Milestone Review

**Input**: Design documents from `/specs/004-review-followups/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/behavior-and-docs-contract.md](contracts/behavior-and-docs-contract.md), [quickstart.md](quickstart.md)

**Tests**: No new test files — US4 strengthens one existing test (that IS its deliverable); US3's behavior freeze is verified by existing suites (Constitution II, C1). No TDD tasks beyond that.

**Organization**: One phase per user story, priority order (P1→P4). Every commit lands green on `sbt scalafmtCheckAll scalafmtSbtCheck compile test` (Constitution II/IV).

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: parallelizable — different files, no unmet dependencies
- **[Story]**: US1–US4, user-story phases only

## Phase 1: Setup

**Purpose**: Land spec artifacts first (Constitution IV), confirm green baseline.

- [ ] T001 Commit all 004 spec artifacts as `docs(speckit): add 004-review-followups spec, plan, and design artifacts` — includes specs/004-review-followups/ (spec.md, plan.md, research.md, data-model.md, quickstart.md, checklists/, contracts/), .specify/feature.json, and the CLAUDE.md plan-pointer update; nothing else in the commit
- [ ] T002 Verify baseline green: run `sbt scalafmtCheckAll scalafmtSbtCheck compile test` at repo root; must pass before any story work

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Traceability wiring required before any implementation commit can reference its issue (Constitution IV: 1 issue = 1 commit; no milestone = no merge).

**⚠️ CRITICAL**: Both are repo-visible mutations on galax-io/gatling-jdbc-plugin — confirm with the user before executing each.

- [ ] T003 Create milestone `v1.3.1` on galax-io/gatling-jdbc-plugin (`gh api repos/galax-io/gatling-jdbc-plugin/milestones -f title=v1.3.1 …`) with a one-line description referencing the v1.3.0 post-merge review
- [ ] T004 Create four issues tied to milestone v1.3.1, one per story, each self-contained with its review finding and spec FR reference: (1) Java builder upgrade note [US1/FR-001], (2) batch ordering docs [US2/FR-002], (3) consolidate check-failure KO path [US3/FR-003], (4) strengthen check-chain regression test [US4/FR-004]; record the four issue numbers for use in T007/T010/T013/T016 commit messages

**Checkpoint**: issue numbers known — story phases may proceed, in any order or in parallel where marked.

---

## Phase 3: User Story 1 — Upgrading Java users are warned about the builder behavior change (Priority: P1) 🎯 MVP

**Goal**: README and v1.3.0 release notes carry the copy-on-write upgrade note (change + consequence + correct pattern, per contract C2).

**Independent Test**: read both placements; every C2 content element present; example consistent with `QueryActionBuilderBranchSpec`-proven behavior (spec US1 acceptance).

### Implementation for User Story 1

- [ ] T005 [US1] Add the upgrade-note block to README.md under `## Checks` → `### Java` (~line 385): change since 1.3.0, silent-loss consequence, before/after example, branching reassurance — content per contracts/behavior-and-docs-contract.md C2
- [ ] T006 [US1] Verify T005 content against C2's four-element checklist and against the claims proven by src/test/scala/org/galaxio/gatling/jdbc/javaapi/QueryActionBuilderBranchSpec.scala (loss-when-ignored; original-instance-unchanged); fix any drift
- [ ] T007 [US1] Commit README change as `docs(readme): warn that Java QueryActionBuilder.check returns a new builder (#<issue1 from T004>)` — gate-green
- [ ] T008 [US1] Amend published v1.3.0 release notes append-only: fetch current body (`gh release view v1.3.0 --json body`), append `### Upgrade notes` section with C2 content, push via `gh release edit v1.3.0 --notes-file …`; existing sections byte-identical — **outward-facing published artifact: obtain explicit user confirmation immediately before executing**

**Checkpoint**: US1 fully delivered — both placements live, MVP complete.

---

## Phase 4: User Story 2 — Batch authors can predict grouping and ordering (Priority: P2)

**Goal**: README batch section states order-preservation, adjacency-merge, group-count implication, guidance, atomicity-unchanged (contract C3).

**Independent Test**: read `### Batch Operations`; a reader can derive A,B,A → 3 groups and A,A,B → 2 without source access (spec US2 acceptance, SC-002).

### Implementation for User Story 2

- [ ] T009 [US2] Add "Execution order and grouping" paragraph to README.md under `### Batch Operations` (~line 304, after the Java/Kotlin example): the three rules + A,B,A vs A,A,B example + adjacency guidance + transactional-behavior-unchanged sentence — content per C3 (no performance numbers); consistent with src/test/scala/org/galaxio/gatling/jdbc/db/BatchOrderSpec.scala
- [ ] T010 [US2] Commit as `docs(readme): document batch execution ordering and adjacent-grouping rule (#<issue2 from T004>)` — gate-green

**Checkpoint**: US1 and US2 independently delivered (note: T005 and T009 both edit README.md — sequential, not parallel).

---

## Phase 5: User Story 3 — Maintainers evolve check-failure reporting in one place (Priority: P3)

**Goal**: single KO-reporting call site in the query action; observable behavior byte-identical (contract C1).

**Independent Test**: grep shows one `"Check ERROR"` construction site; C1's four verification suites pass unmodified (spec US3 acceptance, SC-003).

### Implementation for User Story 3

- [ ] T011 [P] [US3] In src/main/scala/org/galaxio/gatling/jdbc/actions/DBQueryAction.scala (lines ~41–69): introduce nested `def failCheck(failedSession: Session, message: String): Unit = executeNext(failedSession.markAsFailed, startTime, received, KO, next, resolvedName, Some("Check ERROR"), Some(message))` inside the `Success(result)` branch; replace the `Some(validation.Failure(errorMessage))` branch body with `failCheck(newSession, errorMessage)` and the `NonFatal(e)` catch body with `failCheck(session, e.getMessage)`; keep the existing `(#78)` comment; design per research.md R2
- [ ] T012 [US3] Verify C1: `grep -c '"Check ERROR"' src/main/scala/org/galaxio/gatling/jdbc/actions/DBQueryAction.scala` returns 1; run `sbt "testOnly org.galaxio.gatling.jdbc.actions.ThrowingCheckSpec org.galaxio.gatling.jdbc.actions.SessionMarkAsFailedSpec org.galaxio.gatling.jdbc.actions.ActionSessionFailureSpec org.galaxio.gatling.jdbc.actions.QueryActionBuilderCheckChainSpec"` — all green, zero assertion changes
- [ ] T013 [US3] Commit as `refactor(actions): consolidate duplicated check-failure KO path (#<issue3 from T004>)` — gate-green

**Checkpoint**: US3 delivered; behavior freeze proven by unmodified suites.

---

## Phase 6: User Story 4 — Check-chaining regression coverage stands on its own (Priority: P4)

**Goal**: third check-chain test detects replace-instead-of-append standalone (contract entity RegressionProbe, research R1).

**Independent Test**: quickstart §3 mutation check — with the #79 regression reintroduced, the third test fails by itself; restored, it passes (spec US4 acceptance, SC-004).

### Implementation for User Story 4

- [ ] T014 [P] [US4] In src/test/scala/org/galaxio/gatling/jdbc/actions/QueryActionBuilderCheckChainSpec.scala, third test ("report a failure when any of three chained checks fails"): change arrangement from `passing → failingMid → failingLast` to `passing → failingMid → passingLast` (rename `failingLast` accordingly) and add `koResponses.head.responseCode shouldBe Some("Check ERROR")` for parity with the second test; keep the test name and the size-1 KO assertion
- [ ] T015 [US4] Run the quickstart §3 mutation check: `perl -pi -e 's/copy\(checks = checks \+\+ newChecks\)/copy(checks = newChecks)/' src/main/scala/org/galaxio/gatling/jdbc/actions/actions.scala` → `sbt "testOnly …QueryActionBuilderCheckChainSpec"` must FAIL (third test included) → `git checkout -- …/actions.scala` (use /usr/bin/git if the wrapper interferes) → rerun, must pass
- [ ] T016 [US4] Commit as `test(actions): make check-chain regression test detect replacement standalone (#<issue4 from T004>)` — gate-green

**Checkpoint**: all four stories delivered.

---

## Phase 7: Polish & Cross-Cutting

**Purpose**: end-to-end validation and delivery.

- [ ] T017 Full gate at repo root: `sbt scalafmtCheckAll scalafmtSbtCheck compile test` and `sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"` — both green; `git diff --stat main -- src/test` shows only QueryActionBuilderCheckChainSpec.scala
- [ ] T018 Run the full quickstart.md validation (§1–§5) top to bottom; every expected outcome met
- [ ] T019 Open one PR `004-review-followups` → `main`, tied to milestone v1.3.1, closing all four T004 issues — **confirm with user before creating**. Single-PR justification (Constitution IV "1 concern per PR"): the concern is "v1.3.0 post-review follow-ups" as one reviewed batch; per-item traceability preserved via 4 issues + 4 semantic commits. If the user prefers strict stacking, split into four sequential PRs (T007/T010/T013/T016 each as PR head) instead — decide at T019, not before

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: none — start immediately
- **Phase 2 (Foundational)**: after Phase 1; T004 needs T003 (issues attach to milestone) — BLOCKS all story commits (issue numbers in messages)
- **Phases 3–6 (US1–US4)**: after Phase 2; story order P1→P4 by default
- **Phase 7 (Polish)**: after all story phases

### Story Dependencies

- US1–US4 mutually independent (spec-level)
- File-level constraint: T005 (US1) and T009 (US2) both edit README.md → run sequentially
- T011 (US3) and T014 (US4) touch distinct files → parallelizable with each other and with README work
- Within stories: T006→T005; T007→T006; T008→T007; T010→T009; T012→T011; T013→T012; T015→T014; T016→T015

### Parallel Opportunities

```text
After Phase 2 completes:
  Lane A (docs, README.md serial): T005 → T006 → T007 → T008 → T009 → T010
  Lane B (main source):            T011 → T012 → T013
  Lane C (test source):            T014 → T015 → T016
Lanes A/B/C independent; merge at Phase 7.
```

## Implementation Strategy

**MVP = US1 only** (T001–T008): the silent-check-loss warning is the only item with user-harm risk; ship it first. Each subsequent story is an independent green increment; stop-and-validate at every checkpoint. All four commits are `refactor`/`docs`/`test` → next tag stays patch (v1.3.1).

## Notes

- Confirmation-gated tasks (repo/public mutations): T003, T004, T008, T019.
- Commit messages take issue numbers from T004 — do not invent numbers.
- Every commit gate-green; no `feat` commits — patch release per plan Delivery Constraints.
