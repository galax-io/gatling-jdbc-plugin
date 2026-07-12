# Implementation Plan: Statement Concurrency & Resource-Safety Hardening

**Branch**: `001-concurrency-hardening` | **Date**: 2026-07-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-concurrency-hardening/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Close the four remaining open defects of milestone v1.2.0 (#83, #100, #120, #121).
Phase 0 research established that PR #59 already removed every defect mechanism on
current `main` (synchronous `setParams`, `Using.Manager` resource handling, batch
`setQueryTimeout` applied). The work is therefore verification-driven: add the
acceptance/regression test each issue prescribes, on real databases (H2 /
PostgreSQL-Testcontainers) plus JDK-proxy concurrency instrumentation, prove each
guarantee holds, fix any residual gap a test exposes (only realistic candidate:
batch-timeout abort semantics, #83), and close the issues with 1-issue-=-1-commit
traceability. Full analysis: [research.md](research.md).

## Technical Context

**Language/Version**: Scala 2.13.18 (core), Java 17+ (Temurin in CI), Kotlin for facade tests

**Primary Dependencies**: Gatling 3.13.5, HikariCP, sbt; test: ScalaTest, H2, PostgreSQL via Testcontainers

**Storage**: H2 in-memory + PostgreSQL (Testcontainers) — test targets only; plugin itself is DB-agnostic over JDBC

**Testing**: ScalaTest (`AnyFlatSpec`+`Matchers` house style); Gatling example simulation `DebugTest` on H2; `sbt scalafmtCheckAll scalafmtSbtCheck compile test` is the gate

**Target Platform**: JVM 17+ library (Gatling plugin), consumed by Scala/Java/Kotlin load tests

**Project Type**: Single sbt library project with `src/main/{scala,java}` and `src/test/{scala,java,kotlin}`

**Performance Goals**: No throughput regression in statement preparation (binding already serialized post-#59; tests must not add production overhead — instrumentation lives in test code only)

**Constraints**: Backward compatibility of published Scala DSL / javaapi facade (Constitution I, NON-NEGOTIABLE); no new dependencies; no mocks where a real DB path exists (Constitution II); blocking JDBC work stays on the dedicated blocking pool

**Scale/Scope**: 4 issues, ~4 new/extended test specs, 0–1 small production fix (contingent on #83 verification), 3 existing source files in `db/` as the verified surface

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Backward Compatibility | No published signature/default/behavior change for working scenarios | PASS — test-only work planned; contingent #83 fix is internal to `batch` execution, observable only as "configured timeout now honored" (the spec'd intent, FR-003/FR-007) |
| II. Test-First With Real Databases | Behavior guarantees proven on H2/PostgreSQL, not mocks; every commit green on default gate | PASS — each issue's acceptance test runs a real DB path; JDK-proxy instrumentation is issue-prescribed concurrency measurement, always paired with real-DB value verification (see Complexity Tracking) |
| III. Resource & Concurrency Safety | Release-on-all-paths, timeout, thread behavior explicitly considered | PASS — this feature *is* the verification of III; PR text will state pool/timeout/thread analysis per issue |
| IV. Spec-Driven, Semantic Delivery | Spec artifacts land first as `docs(speckit)`; 1 issue = 1 commit; milestone linkage | PASS — spec committed before implementation; commit map in research.md; all 4 issues in milestone v1.2.0 |
| V. Idiomatic Simplicity | Smallest clear change; no premature abstraction | PASS — no production seams added for testability; pool metrics + dynamic proxies keep instrumentation in tests |

**Post-Phase-1 re-check**: PASS — design adds no contracts changes, no new entities, no dependencies.

## Project Structure

### Documentation (this feature)

```text
specs/001-concurrency-hardening/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/
│   └── jdbc-client-behavior.md   # Behavioral contract being verified
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/gatling/jdbc/
├── db/
│   ├── JDBCClient.scala          # Verified surface: withPreparedStatement/withCallableStatement/batch/timeout (possible #83 contingency fix)
│   ├── statements.scala          # Verified surface: synchronous setParams (PS + CS)
│   └── package.scala             # ParamVal ADT, SqlWithParam (read-only for this feature)
└── protocol/
    ├── JdbcProtocol.scala        # queryTimeout wiring (read-only, already covered)
    └── JdbcProtocolBuilder.scala # queryTimeout DSL (read-only, already covered)

src/test/scala/org/galaxio/gatling/jdbc/
├── db/
│   ├── StatementParamsConcurrencySpec.scala   # NEW — #120/#121 proxy instrumentation (max concurrent entry == 1)
│   ├── BatchQueryTimeoutSpec.scala            # NEW — #83 acceptance (slow batch → KO within timeout; fast batch unchanged; no-timeout unchanged)
│   ├── ResourceReleaseOnSyncThrowSpec.scala   # NEW — #100 acceptance (sync throw → original exception; close-counting proxies exactly-once; op-failure × close-failure suppression case; pool metrics; soak loop)
│   ├── PostgreSQLIntegrationSpec.scala        # EXTEND — concurrent value-correctness + stored-proc IN/OUT under load
│   ├── BatchPreparedStatementSpec.scala       # existing (H2 batch values) — reference conventions
│   └── JDBCClientThreadingSpec.scala          # existing (pool growth) — reference conventions
└── actions/
    └── DBCallActionOutParamSpec.scala         # existing OUT-param extraction — reference conventions
```

**Structure Decision**: Single existing sbt project; all new artifacts are ScalaTest
specs under `src/test/scala/org/galaxio/gatling/jdbc/db/`, following the naming and
style of the neighbouring specs. Production edits, if any, confined to
`JDBCClient.batch`.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| JDK dynamic proxy over `PreparedStatement`/`CallableStatement` in tests (brushes against "no mocks where real path exists", Constitution II) | Issues #120/#121 explicitly prescribe a "barrier proxy asserts maxConcurrent setter calls is 1" — concurrency-of-binding is unobservable through a real driver | Real-DB-only test cannot detect overlapped setter calls (drivers may tolerate or mask them); proxy measures the invariant itself. Every proxy test is paired with a real H2/PostgreSQL value-verification test, so the real path stays authoritative |
