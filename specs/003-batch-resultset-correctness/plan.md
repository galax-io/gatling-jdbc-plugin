# Implementation Plan: Runtime Correctness — Batch Execution & ResultSet Mapping

**Branch**: `003-batch-resultset-correctness` | **Date**: 2026-07-19 (revised 2026-07-21) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-batch-resultset-correctness/spec.md`

## Summary

Seven audit-confirmed correctness defects in the JDBC runtime, one shared design ladder:
loud, deterministic failure beats silent wrong data; additive API evolution beats
signature change. The fixes concentrate in the `db` layer — `db/package.scala`
(ResultSet mapping: alias keys, duplicate-label rejection, LOB detachment),
`db/JDBCClient.scala` (throw-based transaction flow with rollback suppression and
eviction, streaming discard path, row cap, autoCommit backstop) — plus a pre-allocation
config check in `protocol/JdbcProtocol.scala` and identifier validation at the two
actions that interpolate dynamic names (`DBInsertAction`, `DBBatchAction`). Each of the
seven issues lands as its own green Conventional Commit with a real-database regression
test (H2; PostgreSQL via Testcontainers where dialect matters). Full analysis in
[research.md](research.md) (R0–R8); note #84 is already half-fixed by the `Using`
rewrite — the remaining work covers rollback masking, the nested-`Try` shape that lets
cleanup failures replace the primary, and the auto-commit-restore transition that would
commit a partial batch after a failed rollback.

## Technical Context

**Language/Version**: Scala 2.13.18 (core), Java 17+ (javaapi facade, Temurin in CI), Kotlin test coverage

**Primary Dependencies**: Gatling 3.13.5, HikariCP 7.1.0, sbt; no new dependencies required

**Storage**: H2 (in-memory, primary test vehicle), PostgreSQL via Testcontainers (dialect-sensitive tests: label case, `bytea`/`text`/`xml`, cursor streaming)

**Testing**: ScalaTest (`sbt test`), Gatling example simulation `DebugTest` on H2, existing test-support (`H2`, `CloseCountingDataSource`)

**Target Platform**: JVM library (published Gatling plugin), consumed from Scala/Java/Kotlin load tests

**Project Type**: Single library project (`src/main/scala` + `src/main/java` facade + mixed tests)

**Performance Goals**: Load-generator overhead must not grow: no per-row metadata calls (hoisted), discard path allocates O(1) per query and streams on PostgreSQL (transaction-scoped drain); identifier validation is a per-request check on short strings

**Constraints**: Published Scala DSL + Java facade stay source-compatible; additions follow constitution I's additive path ("new optional parameters with safe defaults" — the `QueryActionBuilder.maxRows` field; synthetic `apply`/`copy` shifts documented in release notes); all resource release on every path (constitution III); each issue = one green commit (constitution IV)

**Scale/Scope**: 7 issues → ~9 main-source files touched (7 Scala + 2 new: `db/SqlIdentifier.scala`, `db/exceptions.scala`) + Java facade passthrough; ~7 new/extended test suites; 2 new public exception types, 1 new DSL method, 1 new client method + 1 capped overload

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-design | Post-design |
|---|---|---|---|
| I. Backward Compatibility | No published signature/default changes; behavior changes justified as defect fixes | PASS — all API changes additive (`.maxRows`, `executeSelectDiscard` + capped overload, new exception types). Three observable behavior changes (alias keys, duplicate-label KO, autoCommit=false startup rejection) are defect fixes per spec Assumptions, flagged for release notes | PASS — contracts/api-surface.md enumerates every addition and behavior change; `maxRows` lands via constitution I's sanctioned additive path (new optional parameter with safe default; source-compatible, synthetic `apply`/`copy` shift release-noted; binary bridge/MiMa deliberately out of scope — would be a new dependency requiring approval) |
| II. Test-First With Real Databases | Every behavior change covered by a real-DB regression test in the same commit | PASS — R8 maps each issue to H2/PostgreSQL tests; sole proxy exception (#84 rollback failure) wraps a real H2 connection and matches the issue's own acceptance wording | PASS — quickstart.md defines per-story validation runs matching task-defined spec names; SC-005 verified in CI at 1M rows; `DebugTest` stays green |
| III. Resource & Concurrency Safety | All acquire/release paths reviewed; no blocking-thread hazards introduced | PASS — LOB detachment happens *inside* the existing `Using.Manager` scope with `free()` on failure paths; discard path drains within a plugin-managed transaction scope; batch failure flow throws the primary so `Using` suppression applies, and a connection with a failed rollback is evicted (never restored through a committing `setAutoCommit(true)`); autoCommit rejection runs **before** pool/executor allocation — nothing leaks on the rejection path; no new threads, pools, or blocking calls | PASS — data-model.md states lifecycle rules per entity; T027 gates that each PR body restates pool/transaction/timeout/error/thread considerations per this principle |
| IV. Spec-Driven, Semantic Delivery | Spec landed first; 1 issue = 1 commit; milestone linkage | PASS — spec merged via PR #151 (docs-only, milestone v1.4.0); this plan follows the same route; FR↔issue mapping is 1:1 | PASS — tasks generated per issue in priority order (US1…US6); linkage audited by T027 |
| V. Idiomatic Simplicity | Smallest clear change; no premature abstraction | PASS — no new frameworks/abstractions; one validator object, two exception classes, one transaction-scope helper (shared by batch and discard paths), one client method + overload, one DSL method | PASS — Complexity Tracking empty; rejected-as-overengineering alternatives recorded in research (synthetic placeholder names, Expression-typed columns API, duplicate-preserving representation) |

**Initial Constitution Check: PASS** (no violations to justify). **Post-Design Check: PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/003-batch-resultset-correctness/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions R0–R8 with evidence against current main
├── data-model.md        # Phase 1 — entities, representations, validation rules
├── quickstart.md        # Phase 1 — per-story validation guide
├── contracts/
│   ├── api-surface.md          # Additive API + behavior-change contract
│   ├── identifier-grammar.md   # Dynamic identifier grammar (accept/reject)
│   └── result-mapping.md       # Label keying + value detachment contract
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/gatling/jdbc/
├── db/
│   ├── package.scala            # record(): label keys, duplicate rejection, LOB detachment (#122, #123, #87)
│   ├── JDBCClient.scala         # throw-based tx flow: rollback suppression + eviction (#84);
│   │                            #   streaming discard path + row cap (#86); autoCommit backstop (#88)
│   ├── exceptions.scala         # NEW — DuplicateColumnLabelException (#123)
│   └── SqlIdentifier.scala      # NEW — identifier grammar validator + InvalidSqlIdentifierException (#124)
├── actions/
│   ├── DBQueryAction.scala      # route checks-empty → discard path; carry maxRows (#86)
│   ├── DBInsertAction.scala     # validate table (dynamic) + column (static) identifiers (#124)
│   ├── DBBatchAction.scala      # validate table (dynamic) + column (static) identifiers (#124)
│   └── actions.scala            # QueryActionBuilder.maxRows DSL (#86)
└── protocol/
    └── JdbcProtocol.scala       # pre-allocation autoCommit=false rejection in newComponents (#88)

src/main/java/org/galaxio/gatling/javaapi/
└── actions/QueryActionBuilder.java   # maxRows passthrough (#86)

src/test/scala/org/galaxio/gatling/jdbc/
├── db/                          # per-issue regression specs (H2, PostgreSQL, proxy)
├── db/testsupport/              # failure-injecting proxy DataSource for #84
├── actions/                     # identifier-validation + retention-routing action specs
└── protocol/                    # autoCommit rejection via JdbcProtocolBuilderSpec pattern (#88)

src/test/kotlin/                 # Kotlin maxRows usage coverage (#86)
```

**Structure Decision**: Existing single-library layout; no new modules. Two new
main-source files (`db/SqlIdentifier.scala`, `db/exceptions.scala`); everything else
edits files listed above.

## Complexity Tracking

No constitution violations — table intentionally empty.
