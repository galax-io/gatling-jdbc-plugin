# Feature Specification: Check Semantics & Concurrency Correctness (Milestone v1.3.0)

**Feature Branch**: `002-check-semantics-concurrency`

**Created**: 2026-07-19

**Status**: Draft

**Input**: User description: "Specify the open bugs in milestone https://github.com/galax-io/gatling-jdbc-plugin/milestone/6, first explaining why each one is worth fixing."

## Why each bug is worth fixing

Baseline audit `a8d0401bd92ea694f5f550dd279e61d5581408c3` found 5 open P0 defects, all sharing one property: **the plugin silently produces wrong load-test results instead of failing loudly**. A load-testing tool that lies about pass/fail state or hangs virtual users is worse than one that crashes — teams ship on top of numbers the plugin fabricated.

- **#77 — Unresolved `requestName` EL hangs the VU.** A single missing session attribute in a request name freezes that virtual user forever: no KO, no failed session, no `next`. At scale this looks like the target system stalling under load, when it's actually the harness stuck. Wastes engineering time chasing a phantom performance issue and can stall an entire simulation run.
- **#78 — Throwing JDBC checks hang the VU.** A user-supplied check predicate that throws currently escapes `onComplete` unhandled — no KO, no stats entry, VU stuck. Any check bug in test code (not app code) silently corrupts the whole run's pass/fail count and can hang the simulation. Undermines the core promise of a check: "assert or fail visibly."
- **#79 — Scala `.check()` replaces previous checks instead of appending.** Chaining `.check(a).check(b)` silently drops `a`. Users believe both assertions run; only the last one does. A required correctness assertion can be silently disabled by adding a second one — a data-integrity bug in the testing tool itself, invisible until someone audits generated SQL by hand.
- **#80 — Java `QueryActionBuilder` mutates shared branches.** The builder returns `this` instead of a copy, so two scenario branches built from one shared base end up sharing the last-added check. Branch A's "expect non-empty" and branch B's "expect empty" contaminate each other depending on build order — nondeterministic, hard to reproduce, and breaks the fundamental branch-independence assumption every builder API implies.
- **#82 — Batch execution reorders non-contiguous SQL.** Grouping queries by SQL text before execution turns `insert A -> update all -> insert B` into `insert A -> insert B -> update all` (or similar), producing a different final database state than the scenario specified. A load test's batch step is supposed to model a real transaction shape; silently reordering it means the tool exercises a different write pattern than the one under test — and, against a shared/writable target, can leave data in a state the test author never intended.

Net effect if left unfixed: v1.3.0 ships a plugin where check chaining, branch composition, batch ordering, and error/timeout paths cannot be trusted — every one of these is a "trust the test tool" defect, not a nice-to-have.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Checks fail loudly instead of hanging the simulation (Priority: P1)

A performance engineer writes a JDBC query action with a request name built from a session attribute, and/or a custom check predicate. When the attribute is missing or the predicate throws, the virtual user must report a KO and continue to the next action — never hang.

**Why this priority**: A hung VU silently stalls or skews an entire simulation run (throughput, active-users, and duration metrics all become meaningless). This is the most severe class of defect because it can invalidate an entire load test, not just one assertion.

**Independent Test**: Run an H2-backed scenario where (a) `requestName` references a missing session attribute, and (b) a `simpleCheck` predicate throws a `RuntimeException`. In both cases, verify exactly one KO response, one failed session, one stats entry, and one `next` invocation — no hang, no timeout wait.

**Acceptance Scenarios**:

1. **Given** a JDBC query action whose request name interpolates `#{missingAttr}`, **When** the session lacks `missingAttr`, **Then** the action reports one KO with a resolvable fallback name, marks the session failed, and calls `next` exactly once.
2. **Given** a JDBC query action with a check predicate that throws a non-fatal exception, **When** the query succeeds but the check evaluation throws, **Then** the action reports one KO through the normal failure path, marks the session failed, and calls `next` exactly once.

---

### User Story 2 - Chained and branched checks behave as declared (Priority: P1)

A test author chains multiple `.check(...)` calls on a Scala query action, or derives two Java action builders from one shared base builder. Both checks must run (Scala), and each derived branch must keep its own checks independent of the other branch (Java).

**Why this priority**: Silently dropped or cross-contaminated checks mean the plugin executes different assertions than the ones the author wrote, with no error or warning — a correctness bug in the testing tool that can mask real application bugs under load.

**Independent Test**: (a) Chain two Scala `.check()` calls where the first check would fail; run over H2 and confirm the first check's failure is reported (proving it wasn't dropped). (b) Build two independent Java action branches from one shared builder — one expecting an empty H2 result, one expecting non-empty — and confirm each branch's outcome matches its own expectation regardless of build order.

**Acceptance Scenarios**:

1. **Given** a Scala query action with `.check(checkA).check(checkB)`, **When** the action executes against H2, **Then** both `checkA` and `checkB` are evaluated and either one's failure is reported.
2. **Given** a Java `QueryActionBuilder` base and two branches derived by calling `.check(...)` differently on each, **When** both branches execute against H2, **Then** each branch's result reflects only the checks explicitly added to that branch, independent of the other branch or the order the branches were built in.

---

### User Story 3 - Batch operations preserve declared execution order (Priority: P2)

A test author defines a batch of JDBC operations mixing inserts and updates in a specific sequence (e.g., insert A, update all, insert B) to model a real transaction shape. The plugin must execute them in that order rather than grouping by SQL text.

**Why this priority**: Order matters for the resulting database state and for what write pattern is actually exercised under load; a reordered batch silently tests something other than what was specified. Scoped below P1 because it affects batch-action users specifically, while P1 stories affect every action type.

**Independent Test**: Run an H2-backed batch scenario with `insert A -> update all -> insert B` and assert the final row state and per-operation result-count order match the declared sequence, not a SQL-grouped sequence.

**Acceptance Scenarios**:

1. **Given** a batch of `insert(A)`, `update(all rows)`, `insert(B)`, **When** the batch executes against H2, **Then** the final table state matches executing those three statements in the declared order (B is unaffected by the update, A is).
2. **Given** the same batch, **When** execution completes, **Then** per-statement result counts are reported in the declared order, not grouped by identical SQL.

---

### Edge Cases

- What happens when a `requestName` EL expression references an attribute that is present but not a `String` (type mismatch)? Must still resolve to one KO and `next`, not a hang or an uncaught exception.
- Check predicates exist only on the query action today (research.md scope confirmation) — the thrown-check contract (#78) therefore applies to query alone, while the name-resolution contract (#77) must hold for every action type; if checks are ever added to another action type, the same one-KO/one-`next` contract extends to it.
- What happens when three or more `.check()` calls are chained, and more than one fails? All must be evaluated; reported failure(s) must reflect actual evaluation, not just the last one.
- What happens when a batch mixes non-contiguous identical SQL that legitimately should still be groupable for driver-level `addBatch` efficiency (e.g., three consecutive identical inserts)? Grouping is acceptable only when it does not change the *observable* order of distinct SQL relative to other statements.
- What happens when a Java builder branch is derived, and the *original* base builder is reused for a third branch after the first two branches were created? The base must remain unaffected by either derived branch's checks.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST resolve `requestName` exactly once per action invocation and report a single KO plus a stable fallback action name when the EL expression cannot be resolved, instead of re-evaluating it in the failure path.
- **FR-002**: System MUST catch exceptions thrown by user-supplied check predicates or completion callbacks and route them through the same KO/failed-session/`next` path used for a normal check failure, for every JDBC action type that supports checks — currently the query action only; the other action types (insert, batch, raw SQL, stored procedure) expose no checks (verified in research.md scope confirmation), so no guard is added there.
- **FR-003**: System MUST guarantee that for every action type, exactly one `next` invocation and exactly one stats entry occur per action execution, regardless of whether the failure originated from the query, the check, or name resolution.
- **FR-004**: Scala query actions MUST append new checks to existing checks via `.check(...)` (checks ++ newChecks) rather than replacing the prior check list.
- **FR-005**: System MUST document the execution order guarantee for chained checks (declaration order) so behavior is predictable, not just correct.
- **FR-006**: Java `QueryActionBuilder` (and any other javaapi action builder with the same mutate-and-return-this pattern) MUST return a new, independent builder instance from `.check(...)` and any other branching method, leaving the original builder and any other previously derived branch unmodified.
- **FR-007**: Batch execution MUST preserve the original declared order of distinct, non-contiguous SQL operations; grouping of identical SQL for driver efficiency is permitted only when it does not change the relative order of operations with different SQL.
- **FR-008**: System MUST NOT alter the observable public API signatures of `.check()`, `QueryActionBuilder`, or batch action builders in a way that breaks existing user scenario code — fixes are behavioral, not signature changes.

### Key Entities *(include if feature involves data)*

- **JDBC Action** (query / insert / batch / raw SQL / stored procedure): a single Gatling action invocation against the database; owns a request name, zero or more checks, and produces exactly one outcome (OK/KO) and one `next` call.
- **Check**: a user-declared assertion (Scala or Java) attached to an action; a chain or branch of checks must reflect exactly the checks the user declared for that specific action/branch.
- **Batch Operation Sequence**: an ordered list of distinct SQL statements (with parameters) submitted as one batch; execution order and per-statement result counts are observable outcomes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the 5 open milestone defects (#77, #78, #79, #80, #82) have a passing H2-backed regression test that reproduces the original failure scenario and proves the fix.
- **SC-002**: A virtual user is never left without a `next` call — verified by a test matrix covering an unresolved request name on every action type (query, insert, batch, raw SQL, stored procedure) and a thrown check predicate on the query action, the only action type that supports checks (see research.md scope confirmation).
- **SC-003**: A load test author who chains checks or derives builder branches gets exactly the assertions they declared, with zero silent drops or cross-branch contamination, verified by automated tests rather than manual inspection.
- **SC-004**: A batch of mixed insert/update operations produces the same final database state as executing the statements individually in declared order, verified by an H2 assertion on final row state.
- **SC-005**: No published Scala or Java API signature changes; existing simulations compiled against the current release continue to compile unchanged against the fixed release.

## Assumptions

- Scope is limited to the 5 currently **open** milestone issues (#77, #78, #79, #80, #82). #144 (changelog commit-filter bug) is already closed via PR #145 and is out of scope here.
- "Every action type" in acceptance intent (#77/#78) is read as: query, insert, batch, raw SQL, and stored-procedure actions — the full set under `actions/`.
- H2 is an acceptable and sufficient database for regression tests proving these fixes, consistent with existing test conventions in this repo; PostgreSQL/Testcontainers is not required unless a fix touches driver-specific behavior.
- "Fallback action name" for unresolved `requestName` (FR-001) is the action's own stable Gatling action name (the `name` member every action already carries) — matching issue #77's "stable action-name fallback" wording; regression tests assert the KO/stats entry is recorded under that name.
- Fixing #82's ordering guarantee does not require abandoning `addBatch`-style grouping entirely — only preserving order for statements that are not contiguous and identical, per the issue's own "only group adjacent identical SQL if required" guidance.
- These are bug fixes to existing, already-published behavior — no new public API surface is introduced; backward compatibility for callers who are not relying on the buggy behavior is preserved by construction.
