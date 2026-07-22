# Tasks: Runtime Safety — Injection Rejection, Secret Redaction & NULL Fidelity

**Input**: Design documents from `/specs/005-injection-secrets-null/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/public-api.md](contracts/public-api.md), [quickstart.md](quickstart.md)

**Tests**: INCLUDED — constitution II mandates test-first with real databases; every fix task is preceded by a failing H2/logback regression task landing in the same commit.

**Organization**: By user story (US1 injection, US2 secrets, US3 NULL), spec-priority order. Repo rule binds task groups to commits: **1 issue = 1 semantic commit**, each green on `sbt scalafmtCheckAll scalafmtSbtCheck compile test`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (user-story phases only)

## Phase 1: Setup

**Purpose**: land spec artifacts before any implementation (repo rule: spec-first).

- [ ] T001 Commit all `specs/005-injection-secrets-null/` artifacts (spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md, checklists/) plus the CLAUDE.md SPECKIT-block pointer update as `docs(speckit): add 005-injection-secrets-null spec/plan/tasks`

---

## Phase 2: Foundational

**Purpose**: verify clean baseline. Deliberately thin — shared code (`Redaction`, log-capture helper) lands inside the first issue commit that needs it, because the 1-issue-1-commit rule forbids infrastructure-only commits mixed into issue history.

- [ ] T002 Run `sbt scalafmtCheckAll scalafmtSbtCheck compile test` on the branch head and confirm green before starting story work (baseline for every subsequent gate)

**Checkpoint**: baseline green — story phases may begin.

---

## Phase 3: User Story 1 — Data values cannot change what a statement does (Priority: P1) 🎯 MVP

**Goal**: hostile procedure names rejected before SQL assembly (#90); batch-WHERE values bound as data, EL-bearing where-strings rejected at build time (#125).

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.actions.CallProcedureValidationSpec org.galaxio.gatling.jdbc.actions.BatchWhereParamSpec"` — hostile inputs → per-request KO / build-time rejection, zero unintended rows changed; valid calls unchanged.

### Issue #90 — procedure-name validation (commit 1)

- [ ] T003 [P] [US1] Write failing H2 regression `src/test/scala/org/galaxio/gatling/jdbc/actions/CallProcedureValidationSpec.scala` (reuse `JdbcActionSpecFixture`): (a) resolved name `proc; DROP TABLE users` → request KO, no SQL executed, target table intact; (b) feeder-resolved hostile name via session attribute → same; (c) valid `schema.proc` qualified name + OUT params still execute OK (mirror `DBCallActionOutParamSpec` setup); (d) quoted identifier accepted
- [ ] T004 [US1] Fix `src/main/scala/org/galaxio/gatling/jdbc/actions/DBCallAction.scala`: `pName <- procedureName(session).flatMap(validIdentifier)` (bridge from `ActionBase`); run full gate; commit `fix(actions): validate procedure identifiers before CALL assembly (#90)` including T003

### Issue #125 — parameterized WHERE (commit 2)

- [ ] T005 [P] [US1] Write failing spec `src/test/scala/org/galaxio/gatling/jdbc/actions/BatchWhereParamSpec.scala`: (a) H2: `where("age = {age}", "age" -> "#{age}")` with feeder value `"1 OR 1=1"` updates zero extra rows; (b) `where("x = '#{v}'")` plain string → `IllegalArgumentException` at DSL construction naming the parameterized overload; (c) placeholder/param mismatch and SET-name collision → deterministic construction-time errors; (d) EL-free static `where("status = 'A'")` works; (e) deprecated `where(Expression)` still functions
- [ ] T006 [US1] Implement Scala side per [contracts/public-api.md](contracts/public-api.md): `where(String)` + `where(String, params*)` overloads with `#{` rejection and placeholder validation in `src/main/scala/org/galaxio/gatling/jdbc/actions/actions.scala` (`BatchUpdateValuesStepAction`, extend `BatchUpdateAction` carrier with where-params), `@deprecated("…", "1.5.0")` on the `Expression` overload; parameterized resolution branch merging where-params into `SqlWithParam` in `src/main/scala/org/galaxio/gatling/jdbc/actions/DBBatchAction.scala`
- [ ] T007 [US1] Mirror in Java facade `src/main/java/org/galaxio/gatling/javaapi/actions/BatchUpdateValuesStepAction.java`: `where(String)` gains `#{` rejection, add `where(String, Map<String, Object>)`; exercise both from the Java usage case `src/test/java/org/galaxio/performance/jdbc/test/cases/JdbcActions.java` (compile-level) and add behavior assertions to `src/test/scala/org/galaxio/gatling/jdbc/javaapi/` facade spec
- [ ] T008 [US1] Run full gate; commit `fix(actions): parameterize batch WHERE values, reject EL predicates (#125)` including T005–T007

**Checkpoint**: US1 complete — injection scenarios from spec US1 all pass; MVP deliverable.

---

## Phase 4: User Story 2 — Secrets and personal data never reach logs or reports (Priority: P2)

**Goal**: password never in `toString`/URLs (#91); KO messages structured and value-free, raw text DEBUG-only (#126); secret-like pool properties never in DEBUG logs (#92).

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.protocol.BuilderRedactionSpec org.galaxio.gatling.jdbc.actions.ErrorMessageSanitizationSpec org.galaxio.gatling.jdbc.protocol.HikariSecretPropsSpec"` — marker secrets/PII absent from every captured artifact.

### Issue #91 — builder toString redaction (commit 3; creates shared `Redaction` + log helper)

- [ ] T009 [P] [US2] Write logback capture helper `src/test/scala/org/galaxio/gatling/jdbc/db/testsupport/LogCapture.scala`: installs an in-memory appender (optionally forcing DEBUG) on named loggers around a block, returns captured lines, always detaches — test scope only
- [ ] T010 [P] [US2] Write failing spec `src/test/scala/org/galaxio/gatling/jdbc/protocol/BuilderRedactionSpec.scala` (extend existing `JdbcProtocolBuilderSpec` patterns): (a) builder step `toString` with marker password `s3cr3t-MARKER-91` never contains marker; (b) URL `jdbc:postgresql://usr:pw-MARKER@host/db` renders `usr:***@`; (c) `copy`/`apply` still work; (d) malformed URL → `<redacted url>`, no throw; (e) FR-005 "inside exception messages": provoke a real failure with the config in context (e.g. pool construction on an invalid/unreachable URL built from this step with marker password + URL creds) and assert no marker appears in any `getMessage` across the exception chain or in captured log output
- [ ] T011 [US2] Create `src/main/scala/org/galaxio/gatling/jdbc/protocol/Redaction.scala` (`private[jdbc]`: `redactUrl`, `isSecretProperty` per [data-model.md](data-model.md) §4) and `override def toString` in `src/main/scala/org/galaxio/gatling/jdbc/protocol/JdbcProtocolBuilder.scala` (`JdbcProtocolBuilderConnectionSettingsStep`); run full gate; commit `fix(protocol): redact credentials in builder toString and URLs (#91)` including T009–T010

### Issue #126 — structured KO messages (commit 4)

- [ ] T012 [US2] Write failing spec `src/test/scala/org/galaxio/gatling/jdbc/actions/ErrorMessageSanitizationSpec.scala`: H2 unique-constraint violation with marker email `pii-MARKER@x.io` in the row → KO message matches `ClassName [SQLState=…, code=…]` grammar, ≤512 chars, no marker; raw driver text (with marker) appears on the action logger at DEBUG (via `LogCapture`); suppressed-cleanup suffix contains class names + SQLState only (extend scenarios from `BatchCleanupDiagnosticsSpec`); `InvalidSqlIdentifierException` reports class-name-only on the KO path; a message hitting the 512 bound is truncated ending with `…` (assert marker present exactly when truncation applies)
- [ ] T013 [US2] Implement `Redaction.koMessage` (grammar + 512 bound + plugin-exception allowlist per [data-model.md](data-model.md) §4), replace `messageWithSuppressed` usage in `src/main/scala/org/galaxio/gatling/jdbc/actions/ActionBase.scala` (`reportError` + DEBUG raw-throwable log at the same point); adjust existing assertions pinned to raw messages in `src/test/scala/org/galaxio/gatling/jdbc/actions/BatchCleanupDiagnosticsSpec.scala` and any other spec the gate flags; run full gate; commit `fix(actions): report structured value-free KO messages (#126)` including T012

### Issue #92 — Hikari secret properties (commit 5; probe-first, scope = failing assertions)

- [ ] T014 [US2] Write probe spec `src/test/scala/org/galaxio/gatling/jdbc/protocol/HikariSecretPropsSpec.scala`: start real `HikariDataSource` on H2 with markers in four positions (config password, `addDataSourceProperty("sslpassword", …)`, `addDataSourceProperty("token", …)`, URL-embedded creds), capture `com.zaxxer.hikari` + plugin loggers at DEBUG via `LogCapture`, assert no marker in any line — record which assertions fail (R5: where Hikari already masks, the assertion just pins it)
- [ ] T015 [US2] Close exactly the gaps T014 proves in `src/main/scala/org/galaxio/gatling/jdbc/protocol/JdbcProtocol.scala` (`newComponents`: redact-or-warn secret-like custom properties via `Redaction.isSecretProperty` before pool construction; URL creds through `Redaction.redactUrl` in plugin-emitted lines); run full gate; commit `fix(protocol): keep secret-like datasource properties out of logs (#92)` including T014

**Checkpoint**: US2 complete — every artifact channel proven marker-free at DEBUG.

---

## Phase 5: User Story 3 — The text "NULL" is data, not a command (Priority: P3)

**Goal**: only JVM `null` / `NullParam` map to SQL NULL (#93).

**Independent Test**: `sbt "testOnly org.galaxio.gatling.jdbc.db.NullFidelitySpec"` — round-trip distinctness of `"NULL"`, `"null"`, `""`, JVM `null`, `NullParam`.

### Issue #93 — sentinel removal (commit 6)

- [ ] T016 [P] [US3] Write failing H2 spec `src/test/scala/org/galaxio/gatling/jdbc/db/NullFidelitySpec.scala`: insert values `"NULL"`, `"null"`, `"Null"`, `""`, JVM `null`, explicit `NullParam` → read back: texts stored verbatim, empty string stays empty, only the last two are SQL NULL; all six distinguishable
- [ ] T017 [US3] Delete the `case (k, "NULL") => (k, NullParam)` line in `src/main/scala/org/galaxio/gatling/jdbc/db/package.scala` (`withParamsMap`); adjust any assertion pinning old behavior in `src/test/scala/org/galaxio/gatling/jdbc/db/SqlWithParamSpec.scala` if the gate flags it; run full gate; commit `fix(db): store literal "NULL" strings as text, not SQL NULL (#93)` including T016 — commit body carries the constitution-I justification + migration pointer ([plan.md](plan.md) Complexity Tracking; release stays v1.5.0, no `!:` marker)

**Checkpoint**: all three stories independently green.

---

## Phase 6: Polish & Delivery

**Purpose**: migration docs, end-to-end proof, milestone linkage.

- [ ] T018 [P] Add migration notes to `README.md` (section for v1.5.0: `"NULL"` sentinel → `NullParam`/JVM `null`; EL-in-`where` → parameterized overloads with before/after snippets per [contracts/public-api.md](contracts/public-api.md) Migration; structured KO message format; security note: secret-property redaction covers the documented name patterns — `password`/`secret`/`token`/`passphrase`/`credential`/`apikey` — names outside them are the user's responsibility). Commit separately; SC-006 requires discoverability from **release notes alone** and git-cliff renders commit **subjects** only, so the subject itself must name the changes: `docs: v1.5.0 migration — "NULL" sentinel removed (use NullParam), where() takes bound params (#93, #125)`
- [ ] T019 End-to-end verification per [quickstart.md](quickstart.md): full gate `sbt scalafmtCheckAll scalafmtSbtCheck compile test` + `sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"` + Java/Kotlin debug simulations if wired in CI; confirm SC-001…SC-006 traceability table all covered
- [ ] T020 Push branch and open PR to `main`: title `fix: runtime correctness — injection, secrets & NULL handling`, body `Closes #90, Closes #91, Closes #92, Closes #93, Closes #125, Closes #126` + constitution-I justification for the two behavior changes; assign milestone `v1.5.0 — Runtime correctness: injection, secrets & NULL handling`; verify with `scripts/check-linkage.sh --pr <N>`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 → Phase 2 → stories**: T001 (spec-first rule) and T002 (baseline) precede everything.
- **US1 (Phase 3)**: independent of US2/US3.
- **US2 (Phase 4)**: internally ordered #91 → #126 → #92 (T011 creates `Redaction` + `LogCapture` that T012–T015 consume). Independent of US1/US3.
- **US3 (Phase 5)**: fully independent — can run any time after Phase 2, in parallel with US1/US2.
- **Phase 6**: after all stories.

### Task-level

- T004 ← T003; T006 ← T005; T007 ← T006; T008 ← T005–T007
- T011 ← T009, T010; T012/T014 ← T009 (helper) & T011 (`Redaction` exists); T013 ← T012; T015 ← T014
- T017 ← T016; T019 ← everything; T020 ← T019

### Parallel Opportunities

- T003 ∥ T005 (different spec files); T009 ∥ T010; T016 ∥ any US1/US2 task; T018 ∥ T019-prep
- Whole stories parallelizable across implementers: US1 (actions), US2 (protocol+reporting), US3 (db) touch disjoint main files — merge order then follows commit order above

## Parallel Example: kick off all first-wave failing tests

```bash
sbt "testOnly org.galaxio.gatling.jdbc.actions.CallProcedureValidationSpec org.galaxio.gatling.jdbc.actions.BatchWhereParamSpec org.galaxio.gatling.jdbc.db.NullFidelitySpec"
```

(T003, T005, T016 authored concurrently — all must fail before their fix tasks start.)

## Implementation Strategy

**MVP = US1** (highest blast radius): T001–T008, stop, validate independently, demo. Then US2 (T009–T015), then US3 (T016–T017) — each checkpoint independently shippable. Single PR at the end closes all six issues (T020); if the milestone needs partial early landing, US1 can ship as its own PR (`Closes #90, #125`) without waiting.

**Format validation**: all 20 tasks follow `- [ ] TNNN [P?] [USn?] description + explicit path`; setup/foundational/polish tasks carry no story label; every fix task names its commit subject.
