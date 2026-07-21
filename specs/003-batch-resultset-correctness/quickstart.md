# Quickstart: Validating Runtime Correctness Fixes

**Feature**: `003-batch-resultset-correctness` | **Date**: 2026-07-19 (revised 2026-07-21)

Validation guide for the seven fixes. Implementation details live in
[plan.md](plan.md) / [research.md](research.md); contracts in [contracts/](contracts/).

## Prerequisites

- JDK 17+, sbt (H2 tests are in-memory, no external services)
- Docker running — only for PostgreSQL Testcontainers specs (alias/case, `bytea`/`text`/`xml` detachment)
- One-time per clone: `bash scripts/install-hooks.sh`

## Full gate (must be green before every push)

```bash
sbt scalafmtCheckAll scalafmtSbtCheck compile test
```

## Per-story validation

Spec names below match the task definitions (T002–T024); globs are the same ones used in
the per-phase checkpoints.

| Story | Issue | Run | Expected |
|---|---|---|---|
| US1 persistence | #88 | `sbt "testOnly *AutoCommitGuard* *JdbcProtocolBuilder*"` | `autoCommit=false` rejected at client construction **and** at the protocol path before pool/executor allocation, message names `batch`/`rawSql` alternatives; default-config update visible from a fresh H2 connection |
| US2 labels | #122, #123 | `sbt "testOnly *ResultSetLabel* *DuplicateColumnLabel*"` | alias keys on H2 + PostgreSQL (engine case asserted); JOIN duplicate → `DuplicateColumnLabelException` naming the label |
| US3 root cause | #84 | `sbt "testOnly *BatchCleanupSuppression*"` | forced `executeBatch` + `rollback`/`close` failures → Future fails with `BatchUpdateException`, cleanup in `getSuppressed`, **no partial batch rows after a failed rollback** |
| US4 bounded rows | #86 | `sbt "testOnly *BoundedRetrieval* *QueryRetentionRouting*"` | 1M-row `SYSTEM_RANGE` drain retains no rows (count reported); `maxRows(n)` with n+1 rows → KO naming cap on **both** paths (with and without checks); `Int.MaxValue` cap valid |
| US5 LOBs | #87 | `sbt "testOnly *LobDetachment*"` | BLOB→bytes, CLOB/NCLOB→String, ARRAY→Vector (incl. array-of-LOB) readable after completion; null/empty cases; PG adds `bytea`/`text`/`xml` |
| US6 identifiers | #124 | `sbt "testOnly *SqlIdentifier* *IdentifierValidation*"` | grammar accept/reject table from [contracts/identifier-grammar.md](contracts/identifier-grammar.md) incl. 128-char accept + brace rejection; malicious table feeder → KO, nothing executed, table intact; invalid static column → KO |

## End-to-end simulation

```bash
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"
```

Must stay green unchanged (constitution II) — proves the example flows (query, insert,
batch, raw SQL, stored procedure) still work on H2 with all fixes in.

## PostgreSQL-only slice

```bash
sbt "testOnly *PostgreSQL*"
```

Covers the engine-dialect assertions (label case, `bytea`/`text`/`xml` detachment,
streaming-relevant behavior). Skipped locally without Docker; CI runs it.

## Success-criteria spot checks

- SC-001/SC-004/SC-006: covered directly by US1/US2/US6 specs above.
- SC-005 (memory independent of row count): verified in CI — the discard path retains
  no per-row objects by construction and `BoundedRetrievalSpec` drains 1,000,000 rows
  (`SYSTEM_RANGE`) at the spec's stated scale. Optional manual heap check: point a
  no-check `DebugTest`-style simulation at a large table and watch heap.
- SC-007 (zero regressions): the full gate + `DebugTest` run unchanged.
