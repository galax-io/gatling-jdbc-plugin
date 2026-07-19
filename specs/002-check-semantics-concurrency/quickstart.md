# Quickstart: Validating the Check Semantics & Concurrency Fixes

## Prerequisites

- Java 17+, sbt on `PATH`.
- No external services required — all 5 regression tests run against H2 (in-memory), per
  constitution II and the existing test conventions in `src/test/scala/org/galaxio/gatling/jdbc/`.
- Working tree on branch `002-check-semantics-concurrency` (or the feature branch created
  for it), with [spec.md](spec.md), [research.md](research.md), and [data-model.md](data-model.md)
  in place.

## 1. Baseline gate (must pass before and after every fix)

```bash
sbt scalafmtCheckAll scalafmtSbtCheck compile test
```

This is the constitution's default gate (Principle II) — every fix commit must be green on
its own.

## 2. Per-defect validation

Each defect gets one new focused spec (exact class names finalized in `tasks.md`; suggested
locations below match existing conventions in the same packages). Run each in isolation
while implementing, then all together at the end.

### #77 — requestName EL resolution hang

```bash
sbt "testOnly org.galaxio.gatling.jdbc.actions.*RequestNameCrash*"
```
Expected (per [contracts/action-check-batch-contract.md](contracts/action-check-batch-contract.md)
Contract 1): for every action type, a request name referencing a missing session attribute
produces exactly one KO, one failed session, one stats entry, one `next` call — no test
timeout/hang.

### #78 — throwing JDBC checks hang the VU

```bash
sbt "testOnly org.galaxio.gatling.jdbc.actions.*ThrowingCheck*"
```
Expected (Contract 2): a `simpleCheck` predicate that throws produces one KO reported as a
check failure, not an uncaught exception or a hang.

### #79 — Scala `.check()` replaces previous checks

```bash
sbt "testOnly org.galaxio.gatling.jdbc.actions.*QueryActionBuilderCheckChain*"
```
Expected (Contract 3, Scala half): `.check(a).check(b)` evaluates both `a` and `b`; either
one's failure is observable.

### #80 — Java `QueryActionBuilder` mutates existing branches

```bash
sbt "testOnly org.galaxio.gatling.jdbc.javaapi.*QueryActionBuilder*"
```
Expected (Contract 3, Java half): two branches derived from one shared base builder produce
independent results (empty vs. non-empty H2 result) regardless of build order.

### #82 — Batch execution reorders non-contiguous SQL

```bash
sbt "testOnly org.galaxio.gatling.jdbc.db.*BatchOrder*"
```
Expected (Contract 4): `insert A -> update all -> insert B` against H2 leaves the table in
the state produced by that exact order (B unaffected by the update, A affected), and
per-statement result counts are reported in declared order.

## 3. Full regression + example simulation

```bash
sbt test
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"
```

`DebugTest` is the Gatling JDBC example CI runs on H2 — it must keep passing unchanged,
confirming none of the 5 fixes altered observable DSL behavior for existing simulations.

## 4. Coverage (optional, matches Codecov gate)

```bash
sbt coverage test coverageReport coverageOff
```

## Done when

- All 5 per-defect specs pass.
- `sbt scalafmtCheckAll scalafmtSbtCheck compile test` is green.
- `DebugTest` still passes unmodified.
- No published Scala/Java signature changed (spot-check: existing callers in
  `src/test/java/org/galaxio/performance/jdbc/test/cases/` still compile without edits).
