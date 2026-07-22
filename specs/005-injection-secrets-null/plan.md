# Implementation Plan: Runtime Safety — Injection Rejection, Secret Redaction & NULL Fidelity

**Branch**: `005-injection-secrets-null` | **Date**: 2026-07-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-injection-secrets-null/spec.md`

## Summary

The spec's criteria drive every technique choice below; decisions are stated with their reasoning first.

**Criterion → technique:**

1. **Values are always data, never statement text** → reuse the existing `SqlIdentifier` allowlist (built for #124, already supports schema-qualified and quoted identifiers) at the one call site that skipped it (stored-procedure names), and route batch-`WHERE` values through the existing named-placeholder binding (`{param}` → `PreparedStatement`) instead of string interpolation. Nothing new is invented; the proven mechanism is applied uniformly.
2. **Artifacts are effectively public** → error messages recorded to Gatling stats are rebuilt from structured, value-free fields (exception class, SQLState, vendor code) rather than filtered free text — filtering driver prose can never prove absence of user data, structure can. Raw messages move to an explicit DEBUG logger channel. Config `toString` and URL credentials are redacted at the source.
3. **Safe by default, escape hatch explicit** → the unsafe dynamic `WHERE` path is rejected at DSL-construction time where it is provably unsafe (a string containing Gatling-EL `#{…}`), and the opaque-`Expression` overload is deprecated and documented as the explicit unsafe escape hatch (#90's "only if required" — it already exists, so it is kept, marked, and warned rather than added).
4. **Blast-radius priority** → implementation order = spec priority: injection (#90, #125) → secrets (#91, #92, #126) → NULL fidelity (#93). Each issue lands as its own green semantic commit (repo rule: 1 issue = 1 commit).

**Per-issue approach** (details in [research.md](research.md)):

| Issue | Fix | Blast radius of change |
|-------|-----|------------------------|
| #90 proc-name injection | `procedureName(session).flatMap(validIdentifier)` in `DBCallAction`; validator already handles `schema.proc` and quoting | 1 line + tests; no API change |
| #125 WHERE injection | new `where(sql, params*)` parameterized overloads (Scala + Java); EL-bearing plain strings rejected at build time; `where(Expression)` deprecated as escape hatch | additive API + 1 behavior change |
| #91 toString password | `override def toString` on the password-bearing builder step; URL credential redaction helper | no signature change |
| #92 Hikari secret props | probe test first (logback capture of pool DEBUG output); redact/warn only the gaps the probe proves | scope set by test evidence |
| #126 error-message PII | KO messages rebuilt from structured fields (class/SQLState/code), bounded; raw text → DEBUG logger; suppressed-summary (#84) aligned to the same rule | message-format change, no API change |
| #93 "NULL" sentinel | delete the `case (k, "NULL")` mapping; JVM `null` and `NullParam` remain the only NULL routes | 1-line removal + migration note |

## Technical Context

**Language/Version**: Scala 2.13.18 (core), Java 17 (javaapi facade, Temurin in CI), Kotlin-compatible usage in tests

**Primary Dependencies**: Gatling 3.13.5 (provided), HikariCP 7.1.0, sbt build; no new dependencies introduced

**Storage**: n/a for the plugin itself; verification against H2 2.4.240 (primary) and PostgreSQL via Testcontainers 1.21.4 where driver-specific behavior matters

**Testing**: ScalaTest 3.2.20; Gatling `DebugTest` example simulation on H2; logback appender capture for log-content assertions (#91, #92, #126)

**Target Platform**: JVM 17+; published library consumed by external Gatling load-test suites (Scala, Java, Kotlin)

**Project Type**: single sbt module — library with a Java facade; no separate services

**Performance Goals**: validation must be invisible at load rates — `SqlIdentifier` is a single linear scan per request (no backtracking regex on the hot path); redaction/sanitization runs only on the error path and at config-build time (once)

**Constraints**: binary backward compatibility for every published signature (constitution I) — additive overloads and deprecation only; two justified *behavior* changes (see Complexity Tracking); every commit green on `sbt scalafmtCheckAll scalafmtSbtCheck compile test`

**Scale/Scope**: 6 issues, ~8 main-source files touched, ~6 test suites added/extended; milestone `v1.5.0` (6 fix commits + spec docs commit)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Verdict | Evidence |
|---|-----------|---------|----------|
| I | Backward Compatibility (NON-NEGOTIABLE) | ✅ PASS under the corruption & injection carve-out (constitution v1.1.0) | No signature removed or changed; new API is additive (`where` overloads, Java `where(String, Map)`); `where(Expression)` deprecated, not removed; case-class `toString` override keeps `apply`/`copy`/`unapply`. The two deliberate *runtime-behavior* changes (#93 sentinel, #125 EL-in-`where` rejection) satisfy all four carve-out conditions: (a) defects misrepresent user-specified data/predicates, (b) written justification in Complexity Tracking + PR bodies, (c) migration note in release notes (T018/T020), (d) regression tests in the same commits (T016/T005). |
| II | Test-First With Real Databases | ✅ PASS | Every fix lands with H2-backed regression tests in the same commit; no database/driver/pool mocks; log assertions use a real logback appender; `DebugTest` stays green. #92 is explicitly probe-test-first. |
| III | Resource & Concurrency Safety | ✅ PASS | All changes act before SQL is built (validation) or on the error-reporting path (message shaping); no connection/statement/ResultSet lifecycle or pool wiring is touched. Rejections flow through existing KO paths (`crashOnFailure`, `reportError`) — no new resource paths. |
| IV | Spec-Driven, Semantic Delivery | ✅ PASS | Spec/plan land as `docs(speckit)` commit before any fix; 1 issue = 1 semantic `fix(...)` commit referencing its issue; all 6 issues in milestone 8. |
| V | Idiomatic Simplicity | ✅ PASS | Reuses existing mechanisms (`SqlIdentifier`, `{param}` binding, `Validation` KO flow) instead of new abstractions; no control-flow-by-exception beyond existing `Validation` idiom. |

**Post-design re-check (after Phase 1)**: unchanged — design artifacts introduce no new violations; the two behavior deviations remain the only flagged items and stay justified.

## Project Structure

### Documentation (this feature)

```text
specs/005-injection-secrets-null/
├── spec.md              # Feature specification (done)
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1–R7
├── data-model.md        # Phase 1: value/identifier/error/redaction models
├── quickstart.md        # Phase 1: verification guide
├── contracts/
│   └── public-api.md    # Phase 1: DSL/facade API deltas + message-format contract
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/gatling/jdbc/
├── actions/
│   ├── ActionBase.scala          # #126: structured KO message builder (replaces raw getMessage)
│   ├── DBCallAction.scala        # #90: validIdentifier on resolved procedure name
│   ├── DBBatchAction.scala       # #125: parameterized WHERE resolution branch
│   └── actions.scala             # #125: where(sql, params*) overloads, deprecation, EL rejection
├── db/
│   ├── package.scala             # #93: remove "NULL"-string sentinel from withParamsMap
│   └── SqlIdentifier.scala       # unchanged — reused by #90
└── protocol/
    ├── JdbcProtocolBuilder.scala # #91: toString redaction on the password-bearing step
    ├── JdbcProtocol.scala        # #92: secret-like custom-property warn/redact (scope from probe)
    └── Redaction.scala           # #91/#92/#126: shared redaction helpers (new, private[jdbc])

src/main/java/org/galaxio/gatling/javaapi/actions/
└── BatchUpdateValuesStepAction.java  # #125: where(String, Map<String,Object>) + EL rejection

src/test/scala/.../   # per-issue regression suites (H2), logback-capture suites
src/test/java|kotlin/ # facade coverage for the new where overload
```

**Structure Decision**: single existing sbt module; every change stays in the directory that owns the concern (`actions/` validation & reporting, `db/` value mapping, `protocol/` config redaction). One new file (`Redaction.scala`) shared by three issues to avoid triplicating masking logic — `private[jdbc]`, not public API.

## Complexity Tracking

> Constitution I (v1.1.0) permits these two runtime-behavior changes under its corruption & injection carve-out; this table is the written justification the carve-out's condition (b) requires. Each PR carries it explicitly.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| #93: string `"NULL"` no longer maps to SQL NULL (behavior change in a minor release) | Spec criterion 1: silent data alteration corrupts test results; the sentinel makes the legitimate value `"NULL"` unstorable | Keeping the sentinel + adding an opt-out flag preserves the corruption by default and adds permanent config surface for a bug; `NullParam`/JVM `null` already provide the explicit route, so a migration note is the complete remedy |
| #125: `where("… #{el} …")` plain strings rejected at build time (previously interpolated) | Spec criterion 3/4: EL-in-WHERE is the injection vector — feeder values become statement text at load volume | Warning-only keeps the vulnerable path silently working (violates safe-by-default); hard-removing `where(Expression)` breaks compiled consumers (violates constitution I) — so: reject what is provably unsafe, deprecate the opaque overload as the documented escape hatch, provide the parameterized replacement in the same release |

**Version decision (resolved by maintainer, 2026-07-22)**: both deviations are classified as bug fixes, not contract breaks — the release stays **v1.5.0** (minor). Consequently commit subjects use plain `fix(scope): … (#NNN)` — no `!:`/`BREAKING CHANGE` markers — and each PR body carries the constitution-I written justification from the table above plus the migration note reference.

## Phase 0 — Research

Output: [research.md](research.md). All Technical Context unknowns resolved; no NEEDS CLARIFICATION remained (each issue ships an explicit required fix + acceptance test). The one empirical unknown — what HikariCP 7.1.0 actually masks in DEBUG output — is deliberately resolved by a probe test during implementation (R5), because the answer depends on the exact Hikari version and must be re-provable on every upgrade anyway.

## Phase 1 — Design & Contracts

Outputs: [data-model.md](data-model.md) (value-mapping, identifier, redaction, and error-message models), [contracts/public-api.md](contracts/public-api.md) (exact API deltas, deprecations, message-format contract, migration notes), [quickstart.md](quickstart.md) (verification commands and expected outcomes). Agent context (`CLAUDE.md` SPECKIT block) points at this plan.

## Phase 2 — Planning approach (executed by /speckit-tasks, not here)

Tasks will be generated per-issue in spec-priority order (P1 #90 → #125, P2 #91 → #126 → #92, P3 #93), each as test-first pairs (regression test task + fix task) matching the 1-issue-1-commit rule, plus a final docs/migration-note task and a full-gate verification task (`sbt scalafmtCheckAll scalafmtSbtCheck compile test` + `DebugTest`).
