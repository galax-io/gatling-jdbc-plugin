# Phase 0 Research: Statement Concurrency & Resource-Safety Hardening

**Date**: 2026-07-13
**Spec**: [spec.md](spec.md)
**Audit baseline (issues)**: `a8d0401bd92ea694f5f550dd279e61d5581408c3`
**Current baseline (this plan)**: `main` @ `75925b6` (post-#59)

## Central finding

All four open issues (#83, #100, #120, #121) were filed against audit baseline
`a8d0401`, **before** PR #59 ("Fix Deadlock on Backpressure From Connection Pool",
commit `38b99bd`) landed. #59 collapsed each DB operation into a single `Future`
with synchronous `Using`-based resource handling — which structurally removed the
root cause (per-step `Future` boundaries) shared by all four defects.

**On current `main`, every defect mechanism named in the issues is already gone.**
What is missing is the acceptance/regression test each issue demands. This feature
is therefore primarily a **verification and regression-coverage** effort: prove each
fix on a real database, lock it in with tests, and close the issues. Production code
changes only if a test exposes a residual defect.

## Per-issue analysis

### #120 — PreparedStatement setters launched concurrently (P0-A05a)

- **Baseline defect**: `statements.scala:62-91` created one eager `Future` per
  parameter setter and combined them with `reduce`; on a multi-thread executor,
  setters for one statement could interleave.
- **Current state**: `statements.scala:9-26` (`PreparedStatementOps.setParams`) is a
  plain synchronous `foreach` — no `Future`, no `ExecutionContext` in signature. All
  binding happens on the single blocking-pool thread that runs the operation's one
  `Future` (`JDBCClient.withPreparedStatement`, `JDBCClient.scala:68-78`).
- **Decision**: no production change. Add regression tests:
  1. Instrumented-proxy test (`java.lang.reflect.Proxy` over `PreparedStatement`)
     recording concurrent-entry count of setter calls; assert max == 1. Guards
     against reintroduction of per-setter futures.
  2. Real-DB (H2) multi-parameter value-correctness test under concurrent virtual
     users: distinct known values written via `executeUpdate`, read back, assert
     100% correct.
- **Rationale**: issue's own acceptance test prescribes "barrier proxy asserts
  maxConcurrent setter calls is 1 and H2 verifies values".
- **Alternatives considered**: byte-buddy/mockito instrumentation of driver classes —
  rejected (heavier dependency, Constitution: no new deps without approval; JDK
  dynamic proxy over the `java.sql.PreparedStatement` interface suffices).

### #121 — CallableStatement IN/OUT registration launched concurrently (P0-A05b)

- **Baseline defect**: `statements.scala:103-148` eagerly created futures for IN
  setters and `registerOutParameter`; registration could overlap on one statement.
- **Current state**: `statements.scala:28-55` (`CallableStatementOps.setParams`) is a
  synchronous `foreach` over the interpolated index map; IN binding and OUT
  registration happen sequentially on one thread inside
  `JDBCClient.withCallableStatement` (`JDBCClient.scala:80-93`).
- **Decision**: no production change. Add regression tests:
  1. Proxy test over `CallableStatement` asserting max concurrent
     setter/registerOutParameter entry == 1 and that no call overlaps another.
  2. Real stored-procedure test (H2 `CREATE ALIAS` or PostgreSQL function via
     Testcontainers) with both IN and OUT params under concurrent load; assert every
     call returns correct OUT values. `DBCallActionOutParamSpec` already covers
     single-call OUT extraction; the new test adds the concurrency dimension.
- **Rationale / alternatives**: same as #120.

### #83 — Batch statements ignore configured queryTimeout (P0-A07)

- **Baseline defect**: `JDBCClient.scala:169-170` (baseline) prepared batch
  statements without applying `queryTimeoutSeconds`.
- **Current state**: `batch` applies the timeout to every prepared batch statement —
  `JDBCClient.scala:169` (`queryTimeoutSeconds.foreach(stmt.setQueryTimeout)` inside
  `Using(conn.prepareStatement(...))`). Wiring from protocol verified:
  `JdbcProtocolBuilder.queryTimeout` → `JdbcProtocol` → `JDBCClient`
  (`JdbcProtocol.scala:36`, covered by `JdbcProtocolBuilderSpec`).
- **Open verification question**: does a driver-observed timeout actually abort a
  slow batch and surface as `Failure` (KO)? Code sets it; no test proves it fires.
  PostgreSQL honors `setQueryTimeout` via statement cancel; H2 supports it via
  `pg_sleep`-equivalent (`SLEEP()` in a batch row must be arranged carefully — a
  PostgreSQL Testcontainers test with `pg_sleep` in a batched INSERT-SELECT is the
  reliable real path; `PostgreSQLIntegrationSpec` already has slow-query/timeout
  precedent at lines 110-134).
- **Decision**: no production change expected. Add acceptance test: batch whose
  execution exceeds a short configured `queryTimeout` fails within timeout + margin;
  companion tests assert fast-batch success and no-timeout-configured behavior
  unchanged. If the test shows the timeout NOT firing (driver semantics), fix batch
  execution accordingly — that would be the one code change, scoped to `batch`.
- **Alternatives considered**: unit-test that `setQueryTimeout` was called via proxy
  only — rejected as primary test (Constitution II demands real DB path; proxy
  doesn't prove abort semantics). May complement.

### #100 — ResourceFut skips release when use throws synchronously (P1-A24)

- **Baseline defect**: `ResourceFut.scala:14-27` called `f(res)` before installing
  `transformWith` cleanup; a synchronous throw skipped release.
- **Current state**: `ResourceFut.scala` **deleted** by #59. All resource handling is
  `scala.util.Using.Manager` (`JDBCClient.scala:53-93`), which releases every
  registered resource exactly once on all paths including synchronous throws.
  Error-preservation follows `Using`'s severity ranking: an ordinary (non-fatal)
  release failure is suppressed under the original exception, but a fatal throwable
  raised during release (`VirtualMachineError`, `ControlThrowable`,
  `InterruptedException`, `LinkageError`) takes precedence. The contract (G4.2) is
  scoped to exactly this — adversarial review caught the earlier over-claim
  ("always preserves the original").
- **Existing partial coverage**: `PostgreSQLIntegrationSpec:154-176` asserts
  connections return to pool after success and after failed query;
  `JDBCClientFailureSpec` asserts original SQL exceptions surface in the `Try`.
- **Decision**: no production change. Add focused regression tests pinning the
  sync-throw path (scope widened after adversarial review):
  1. Sync failure → `Failure` carries the original exception; pool active-connection
     count returns to 0 (HikariCP `HikariPoolMXBean`).
  2. **Close-counting proxies**: test-only `HikariDataSource` subclass returning
     dynamic-proxy `Connection`s (and proxy statements) that count `close()` per
     resource → assert exactly-once release for every acquired resource, not just
     pool-level return.
  3. **Combined-failure case**: operation fails AND a proxy resource's `close()`
     throws a non-fatal exception → assert the original exception is primary and the
     close failure is attached as suppressed (pins G4.2 as narrowed).
  4. Repeat-loop soak variant guards leak-per-iteration.
- **Alternatives considered**: production-code instrumentation seam to count
  `close()` — rejected (Constitution V, premature abstraction); the test-only
  `HikariDataSource` subclass + JDK proxies deliver per-resource counting with zero
  production change. Pool-metrics-only verification — rejected as sole check after
  adversarial review: zero active connections cannot prove per-resource
  exactly-once release or error-suppression behavior.

## Cross-cutting decisions

- **Test placement**: new specs under `src/test/scala/org/galaxio/gatling/jdbc/db/`
  (mechanism-level) mirroring existing `BatchPreparedStatementSpec` /
  `PostgreSQLIntegrationSpec` / `JDBCClientThreadingSpec` conventions; ScalaTest
  `AnyFlatSpec` + `Matchers` as in existing specs.
- **Real DB choice per test**: H2 in-memory for value-correctness and proxy-free
  paths (fast, CI-cheap); PostgreSQL Testcontainers where driver semantics matter
  (batch timeout abort, stored-procedure OUT under load).
- **No new dependencies**: JDK dynamic proxies + existing ScalaTest/Testcontainers/H2
  stack covers everything. (Constitution: new deps need approval — none needed.)
- **No public API change**: all four issues are internal-behavior; signatures,
  defaults, DSL untouched (Constitution I).
- **Commit mapping** (Constitution IV): 1 issue = 1 commit, `test(db): … (#NNN)` when
  test-only, `fix(db): … (#NNN)` if #83 verification exposes a real driver gap.
  Spec/plan artifacts land first as `docs(speckit): …`.

## Unknowns resolved

Spec contained no [NEEDS CLARIFICATION]. Technical unknowns identified during
planning and their resolutions:

| Unknown | Resolution |
|---------|------------|
| Do the audited defects still exist on `main`? | No — #59 removed all four mechanisms (verified by reading current `db/` sources). |
| Does batch timeout actually abort on a real driver? | To be proven by the #83 acceptance test (PostgreSQL `pg_sleep`); contingency fix scoped if not. |
| How to measure setter concurrency without mocking the DB? | JDK dynamic proxy around the statement interface, per issue-prescribed acceptance test; value correctness still proven on real H2/PG. |
| How to prove release-exactly-once without a test seam? | Test-only `HikariDataSource` subclass + close-counting JDK proxies (per-resource exactly-once), plus HikariCP `HikariPoolMXBean` pool-level check. |

## Adversarial-review adjustments (2026-07-13)

Codex adversarial review (verdict: needs-attention) forced two spec-level corrections
before implementation:

1. **[high] G4.1/G4.2 over-claimed vs `scala.util.Using` semantics.** `Using` picks the
   primary throwable by severity; a fatal release failure can replace the original
   operation failure. Contract narrowed to "non-fatal release failure never masks
   (suppressed)"; #100 test plan extended with close-counting proxies and an
   op-failure × close-failure case. (Related but out of scope: #84 rollback masking.)
2. **[medium] "Deterministic order = declaration order" was unimplementable and
   untested.** Placeholder→index storage is `Map[String, List[Int]]` (unordered; index
   lists built by prepend). FR-001/FR-002 narrowed to "no overlap + every index bound
   exactly once with its declared value"; no cross-index order guarantee. Drivers do
   not require one; requiring it would force a production rewrite with no user-visible
   benefit.
