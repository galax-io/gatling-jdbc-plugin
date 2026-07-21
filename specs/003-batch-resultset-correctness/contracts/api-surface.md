# Contract: Public API Surface & Behavior Changes

**Feature**: `003-batch-resultset-correctness` (revised 2026-07-21)

Constitution I gate: everything here is either **additive** or a **documented defect
fix**; no existing call shape changes. Release notes must carry every entry in the
"Behavior changes" table plus the compatibility note below.

## Additions (new surface)

| Surface | Addition | Contract |
|---|---|---|
| Scala DSL | `QueryActionBuilder.maxRows(n: Int)` | `n > 0` required (`Int.MaxValue` valid); returns a copy (builder immutability preserved); cap enforced on **every** execution path — result exceeding `n` rows → request KO, with or without checks |
| Java facade | `QueryActionBuilder#maxRows(int)` passthrough | delegates to Scala builder; same contract; Java **and Kotlin** usage covered by tests |
| `db` layer | `JDBCClient.executeSelectDiscard(sql, params, maxRows: Option[Int])(consumer: Try[Long] => U)` | drains the full ResultSet inside a plugin-managed read transaction (streams on PostgreSQL), validates duplicate labels before draining, returns row count, retains no rows; enforces the cap when set; used automatically when a query has no checks |
| `db` layer | `executeSelect` capped variant (`maxRows: Option[Int]` parameter with default / overload) | existing call shape unchanged and source-compatible; overflow → failure naming the cap |
| `db` layer | `DuplicateColumnLabelException` (`db/exceptions.scala`) | thrown on duplicate result labels, any path; message names every duplicate |
| `db` layer | `InvalidSqlIdentifierException` / `SqlIdentifier.validate` (`db/SqlIdentifier.scala`) | grammar in [identifier-grammar.md](identifier-grammar.md); message quotes the rejected value |

## Compatibility note (release-notes required)

`QueryActionBuilder` is a published case class; `maxRows` lands as a **new constructor
parameter with a safe default** — the additive path constitution I explicitly sanctions.
This is source-compatible; the synthetic `apply`/`copy` signatures shift, so binaries
compiled against the old artifact that construct/copy the builder directly must
recompile. A binary bridge / MiMa gate is deliberately out of scope (new dependency —
requires approval); flagged in release notes.

## Behavior changes (defect fixes — release-notes required)

| # | Before | After | Justification |
|---|---|---|---|
| #122 | Result keys = physical column names (`getColumnName`); aliases unusable | Keys = column labels (`getColumnLabel`), verbatim case | Aligns with documented `AS`-based usage; identical keys for non-aliased queries |
| #123 | Duplicate labels silently overwrite (last wins) | Operation KO with `DuplicateColumnLabelException` before first row — on every path, incl. no-check discard | Silent data loss → loud failure; workaround: alias uniquely |
| #87 | `Blob`/`Clob`/`NClob`/`SQLXML`/`Array` stored as live locators — always broken by check time | Materialized `Array[Byte]`/`String`/`Vector[Any]`; locators freed even on failure paths | Previous values were never usable after completion; pure fix |
| #88 | `hikariConfig` with `autoCommit=false`: writes report OK, Hikari rolls back | Startup fails **before pool/executor allocation** with `IllegalArgumentException`; message names the supported transaction patterns (`batch` DSL, single-action `rawSql` block); README gains a **Transaction control** section explaining why cross-action transactions are impossible over a pool | OK-without-persistence is the milestone's worst silent failure; the config has no working use case (no commit handle, no connection pinning, Hikari rollback-on-checkin) |
| #84 | Batch cleanup failure can replace the execution failure (rollback path, or any release failure via the nested-`Try` shape); a failed rollback followed by auto-commit restore **commits partial batch work** under a KO | Primary exception always thrown and reported; rollback/close/restore failures in `getSuppressed`; connection with a failed rollback is evicted, never restored through a committing `setAutoCommit(true)` | Root cause preserved for triage; KO must never persist partial writes |
| #86 | Every SELECT fully materialized (and PostgreSQL buffers driver-side regardless of retention) | No-check queries drain without retention inside a plugin-managed read transaction (real streaming on PG); optional `maxRows` cap enforced on all paths; checks-present default unchanged | Observably identical except bounded memory |
| #124 | Table names (feeder-driven) and static column names interpolated raw | Validated against identifier grammar before SQL assembly; invalid → per-request KO, no SQL sent | Blocks accidental statement-structure corruption; scope matches DSL reality (only table names are feeder-reachable) |

## Unchanged (explicit non-goals)

- `JdbcCheck = Check[List[Map[String, Any]]]` — check input type stays a strict,
  fully-materialized list.
- `executeSelect` (existing call shape), `executeUpdate`, `executeRaw`, `call`, `batch`.
- Step-builder protocol path (`url/username/password/...`) — never sets autoCommit,
  unaffected by the #88 rejection.
- `where(...)` fragments and `rawSql` — deliberately free-form SQL, not validated.
- Default retention for queries **with** checks and no cap — still unlimited.
- No `Expression`-typed column-name API (columns stay static `String`s).
