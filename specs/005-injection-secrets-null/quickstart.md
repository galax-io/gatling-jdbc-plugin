# Quickstart: Validating Runtime Safety (005-injection-secrets-null)

**Feature**: [spec.md](spec.md) | **Contracts**: [contracts/public-api.md](contracts/public-api.md)

## Prerequisites

- JDK 17+, sbt (H2 suites need nothing else; PostgreSQL suites need a running Docker daemon for Testcontainers)
- One-time per clone: `bash scripts/install-hooks.sh`

## Full gate (the definition of done for every commit)

```bash
sbt scalafmtCheckAll scalafmtSbtCheck compile test
```

## Per-story verification

Suite FQNs match [tasks.md](tasks.md) (T003, T005, T010, T012, T014, T016); suites are created test-first during implementation.

**US1 — injection cannot change statements (#90, #125), on H2:**

```bash
sbt "testOnly org.galaxio.gatling.jdbc.actions.CallProcedureValidationSpec org.galaxio.gatling.jdbc.actions.BatchWhereParamSpec"
```

Expected: hostile procedure names (`proc; DROP TABLE users`) → per-request KO, target table intact; valid `schema.proc` + OUT params → OK; bound `1 OR 1=1` matches zero extra rows; EL-bearing `where` string → construction-time `IllegalArgumentException` naming the parameterized overload.

**US2 — secrets/PII never reach artifacts (#91, #92, #126), logback capture:**

```bash
sbt "testOnly org.galaxio.gatling.jdbc.protocol.BuilderRedactionSpec org.galaxio.gatling.jdbc.actions.ErrorMessageSanitizationSpec org.galaxio.gatling.jdbc.protocol.HikariSecretPropsSpec"
```

Expected: builder `toString` and captured DEBUG lines contain no marker secrets (`password`, `sslpassword`, `token`, URL creds — the R5 probe); KO messages for constraint violations carrying a marker email contain no marker, match `ClassName [SQLState=…, code=…]`, length ≤ 512; raw driver text appears only on the plugin DEBUG logger.

**US3 — NULL fidelity (#93), on H2:**

```bash
sbt "testOnly org.galaxio.gatling.jdbc.db.NullFidelitySpec"
```

Expected: `"NULL"`/`"null"`/`"Null"` stored as text; JVM `null` and `NullParam` → SQL NULL; empty string stays empty; all distinguishable after round-trip.

## End-to-end sanity (must stay green, unchanged behavior)

```bash
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"
```

## Facade compatibility

Java/Kotlin usage tests compile and pass under `sbt test` (they live in `src/test/java` / `src/test/kotlin`); the new `where(String, Map)` overload is exercised there.

## Success-criteria traceability

| Spec SC | Proven by |
|---|---|
| SC-001 injection rejected, zero extra rows | US1 suites |
| SC-002 zero secret occurrences | US2 redaction suites (probe included) |
| SC-003 zero feeder values in stats, bounded messages | US2 error-message suite |
| SC-004 exact value round-trip | US3 suite |
| SC-005 existing simulations unchanged | full `sbt test` + `DebugTest` |
| SC-006 migration note discoverable | release-notes/README check in the docs task |
