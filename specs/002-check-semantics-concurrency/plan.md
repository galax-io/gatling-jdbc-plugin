# Implementation Plan: Check Semantics & Concurrency Correctness (Milestone v1.3.0)

**Branch**: `002-check-semantics-concurrency` | **Date**: 2026-07-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-check-semantics-concurrency/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Fix 5 open P0 runtime-correctness defects (milestone v1.3.0, issues #77/#78/#79/#80/#82) so
the plugin fails loudly instead of silently producing wrong load-test results: unresolved
`requestName` and throwing check predicates currently hang the virtual user instead of
reporting KO; chained Scala checks and branched Java builders silently drop/share
assertions; batch execution reorders non-contiguous SQL relative to its declared sequence.
Each fix is a small, targeted change confirmed against the current source tree (not just
the audit baseline) — see [research.md](research.md) for the scope confirmation and
per-defect decision, [data-model.md](data-model.md) for the invariant each fix restores, and
[contracts/action-check-batch-contract.md](contracts/action-check-batch-contract.md) for the
exact behavioral guarantee. No public API signature changes.

## Technical Context

**Language/Version**: Scala 2.13.18 (core plugin); Java 17+ (Temurin, `javaapi` facade,
Kotlin-compatible)

**Primary Dependencies**: Gatling 3.13.5 (`gatling-core`/`gatling-core-java`, provided),
HikariCP 6.3.3; test-only: ScalaTest 3.2.20, H2 2.4.240, Testcontainers PostgreSQL 1.21.4 +
PostgreSQL JDBC 42.7.13

**Storage**: N/A — this is a load-testing plugin, not a service with its own storage; test
fixtures use H2 in-memory and PostgreSQL via Testcontainers to validate real JDBC behavior

**Testing**: `sbt test` (ScalaTest specs under `src/test/scala/org/galaxio/gatling/jdbc/`),
`sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"` for the Gatling
example simulation; H2-backed per constitution II, no mocked DB/driver/pool

**Target Platform**: JVM 17+ library published to Sonatype (Gatling load-test plugin)

**Project Type**: single library project (Scala core + Java/Kotlin facade) — existing repo
layout, no new modules

**Performance Goals**: N/A — functional correctness fixes, not a perf feature; the added
exception guards (FR-002) MUST stay O(1) per action (one `NonFatal` catch, no extra
`Future`/thread hop) so no new latency is introduced on the hot query path

**Constraints**: zero published Scala/Java signature changes (constitution I, spec FR-008,
SC-005); every fix ships with a real-H2 regression test proving the original failure
scenario and the fix (constitution II); `JDBCClient.batch`'s commit/rollback atomicity and
resource release must be unaffected by the ordering fix (constitution III)

**Scale/Scope**: 5 defects, 5 production files touched
(`ActionBase.scala`, `DBQueryAction.scala`, `actions.scala`, `QueryActionBuilder.java`,
`JDBCClient.scala` — see [research.md](research.md) scope confirmation for why the other
4 action types need no check-related change), ~5 new regression test files, 0 new
public methods/classes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Backward Compatibility (NON-NEGOTIABLE)** — PASS. All 5 fixes correct broken/hanging
  behavior; none change a method signature, default, or serialized format (spec FR-008).
  Per Release Discipline, these ship as `fix(...)` commits → PATCH version bump, not a
  breaking change — no consumer could have been correctly relying on "hangs forever",
  "silently drops a check", or "reorders my batch" as intended behavior.
- **II. Test-First With Real Databases** — PASS (enforced by process). Every fix gets a
  focused H2-backed regression spec added in the same commit as the fix (see
  [quickstart.md](quickstart.md)); no mocking of DB/driver/pool. `DebugTest` must keep
  passing unmodified.
- **III. Resource & Concurrency Safety** — PASS, with explicit attention required on #82:
  the reordering fix touches `JDBCClient.batch`'s statement-grouping loop but not its
  connection/transaction handling (`withConnectionForBatch`, commit/rollback `.transform`)
  — those are unchanged, so resource release and atomicity are preserved by construction.
  #77/#78 fixes only affect the completion-callback path (`crashOnFailure`, the check-guard),
  not connection/statement lifecycle.
- **IV. Spec-Driven, Semantic Delivery** — PASS. Spec already landed as its own
  `docs(speckit): …` commit (`c8f277d`) before any fix commit. Plan calls for 1 issue = 1
  Conventional Commit (`fix(...): … (#77)`, etc.), each green on its own, all tied to
  milestone v1.3.0 (galax-io/gatling-jdbc-plugin#6).
- **V. Idiomatic Simplicity** — PASS. Each fix is the smallest change that restores the
  documented contract (one-line append for #79, local `NonFatal` guard for #78, no new
  shared abstraction where only one call site exists — see research.md's rejected
  alternatives for why broader abstractions were declined).

No violations requiring the Complexity Tracking table.

## Project Structure

### Documentation (this feature)

```text
specs/002-check-semantics-concurrency/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/
│   └── action-check-batch-contract.md   # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md  # /speckit-specify output
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

Existing single-project Scala/Java library layout — no new modules, only edits inside it:

```text
src/main/scala/org/galaxio/gatling/jdbc/
├── actions/
│   ├── ActionBase.scala          # #77 fix: crashOnFailure — used by all 5 action types
│   ├── DBQueryAction.scala       # #78 fix: guard Check.check(...) against thrown exceptions
│   └── actions.scala             # #79 fix: QueryActionBuilder.check() appends, not replaces
├── check/
│   └── JdbcCheckSupport.scala    # referenced only (simpleCheck) — no change, guard lives in DBQueryAction
├── db/
│   └── JDBCClient.scala          # #82 fix: batch() preserves declared statement order
└── internal/
    └── JdbcCheck.scala           # referenced only (toScalaChecks) — no change

src/main/java/org/galaxio/gatling/javaapi/actions/
└── QueryActionBuilder.java       # #80 fix: final wrapped field + return-new-instance .check()

src/test/scala/org/galaxio/gatling/jdbc/
├── actions/                      # + new specs for #77, #78, #79 (exact names in tasks.md)
├── db/                           # + new spec for #82 (batch ordering)
└── javaapi/                      # + new spec for #80 (builder branch independence)
```

**Structure Decision**: Single existing project (`build.sbt` root project), no structural
change. All 5 fixes land inside the current `src/main/{scala,java}` package layout described
in [AGENTS.md](../../AGENTS.md) ("Structure" section); new tests land alongside existing
specs in the matching `src/test/{scala,java}` package, following existing naming/support
conventions (`JdbcActionSpecSupport`, H2-backed `TestContext`) rather than introducing new
test infrastructure.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

N/A — no Constitution Check violations. All 5 fixes are minimal, in-place corrections with
no new abstractions, modules, or dependencies.
