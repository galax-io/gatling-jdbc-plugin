# Implementation Plan: Post-Review Follow-Ups from the v1.3.0 Milestone Review

**Branch**: `004-review-followups` | **Date**: 2026-07-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-review-followups/spec.md`

## Summary

Four follow-ups from the 2026-07-19 post-merge review of milestone v1.3.0, delivered as a patch-level change with zero observable behavior change:

1. **Consolidate the check-failure KO path** in `DBQueryAction` (two structurally identical `executeNext(... KO ...)` call sites → one local helper), behavior-frozen (spec FR-003).
2. **Publish the Java builder upgrade note** — `QueryActionBuilder.check` became copy-on-write in v1.3.0; ignoring the returned builder now silently registers no checks. Note lands in README (`## Checks` → `### Java`) and as an "Upgrade notes" amendment to the already-published v1.3.0 GitHub release notes (FR-001).
3. **Document the batch grouping/ordering rule** — declared order preserved; identical statements merge only when adjacent; interleaving increases group count. Lands in README `### Batch Operations` (FR-002).
4. **Strengthen the third test** in `QueryActionBuilderCheckChainSpec` so it detects a replace-instead-of-append regression on its own (FR-004): its current arrangement (`passing, failing, failing`) still fails a session under the regression because the *last* registered check fails; rearranged (`passing, failing, passing`) it turns silent under the regression and the assertions catch it.

## Technical Context

**Language/Version**: Scala 2.13.18 (constitution baseline; no syntax used that would diverge under the build's cross-compile settings), Java 17+

**Primary Dependencies**: Gatling 3.13.5, HikariCP; tests: ScalaTest, H2 (in-memory), Testcontainers PostgreSQL (integration, untouched here)

**Storage**: N/A — JDBC plugin; test databases only (H2 via shared `testsupport/H2` fixture)

**Testing**: `sbt scalafmtCheckAll scalafmtSbtCheck compile test` (default green gate); `sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"` must keep passing

**Target Platform**: JVM library (Gatling plugin), consumed from Scala/Java/Kotlin load tests

**Project Type**: single-module sbt library — existing layout, no new directories

**Performance Goals**: N/A — behavior-preserving refactor, documentation, and one test change; nothing on a hot path changes

**Constraints**: zero observable behavior change (FR-003/FR-005); only FR-004's assertion may change in the test suite; scalafmt-clean; patch-level semver (no `feat`, no breaking change)

**Scale/Scope**: 1 main-source file, 1 test file, README (2 sections), 1 published release-notes amendment; 4 semantic commits + 1 spec-docs commit

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Evaluation | Status |
|---|-----------|------------|--------|
| I | Backward Compatibility (NON-NEGOTIABLE) | No public signature, default, or semantic change. Item 1 is internal-only; items 2–3 are documentation of already-shipped v1.3.0 behavior; item 4 is test-only. | PASS |
| II | Test-First With Real Databases | No new behavior to TDD; behavior preservation is proven by the existing H2-backed regression suites passing unmodified (`ThrowingCheckSpec`, `SessionMarkAsFailedSpec`, `ActionSessionFailureSpec`, `QueryActionBuilderCheckChainSpec`). Item 4 strengthens real-database coverage. No mocks introduced. | PASS |
| III | Resource & Concurrency Safety | Item 1 touches `actions/` execute path: pure call-site consolidation inside the existing `Success` branch closure — no new `Future` boundary, no blocking change, no resource lifecycle change. Stated here per principle; nothing else touches `db/` or wiring. | PASS |
| IV | Spec-Driven, Semantic Delivery | Spec artifacts land first in their own `docs(speckit): …` commit; each item then lands as its own Conventional Commit tied to a tracked issue and the next milestone (see Delivery below). | PASS |
| V | Idiomatic Simplicity | Item 1 *implements* "no duplicated code" with the smallest change (nested `def`, no new abstraction, no `ActionBase` API growth). No opportunistic refactors. | PASS |

**Post-design re-check (after Phase 1)**: unchanged — design introduces no new dependencies, no API surface, no structural additions. PASS.

## Project Structure

### Documentation (this feature)

```text
specs/004-review-followups/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── behavior-and-docs-contract.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/gatling/jdbc/actions/DBQueryAction.scala        # item 1: consolidate KO path (lines ~44–69)
src/test/scala/org/galaxio/gatling/jdbc/actions/QueryActionBuilderCheckChainSpec.scala  # item 4: strengthen 3rd test
README.md                                                                   # item 2: "## Checks"→"### Java" note; item 3: "### Batch Operations" paragraph
(external) GitHub release v1.3.0 notes                                      # item 2: append "### Upgrade notes" via gh release edit
```

**Structure Decision**: existing single-module sbt layout; no new source directories; all documentation changes in existing README sections (anchors verified: `### Batch Operations` at ~L304, `## Checks`/`### Java` at ~L365/L385).

## Delivery Constraints (Constitution IV wiring)

- Commit order: `docs(speckit): add 004-review-followups spec and plan` first, then one commit per item, each green on the default gate:
  1. `refactor(actions): consolidate duplicated check-failure KO path (#NNN)`
  2. `docs(readme): warn that Java QueryActionBuilder.check returns a new builder (#NNN)` + release-notes amendment (same concern)
  3. `docs(readme): document batch execution ordering and adjacent-grouping rule (#NNN)`
  4. `test(actions): make check-chain regression test detect replacement standalone (#NNN)`
- Four tracked issues must exist and be tied to the active milestone (expected: v1.3.1) before merge; no milestone = do not merge.
- Amending the published v1.3.0 release notes is an outward-facing mutation of a public artifact — perform only at implementation time as its own explicit, confirmed step (`gh release edit v1.3.0 --notes …`, append-only).
- Version impact: all commits are `refactor`/`docs`/`test` → next tag is patch (v1.3.1) per Release Discipline.

## Complexity Tracking

No Constitution Check violations — table intentionally empty.
