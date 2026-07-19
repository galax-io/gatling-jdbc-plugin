# API Contract: Action Completion, Check Composition, Batch Ordering

This plugin's "interface" is its published Scala DSL and Java/Kotlin `javaapi` facade
(library, not a network service). This document states the behavioral contract each public
entry point MUST satisfy after this feature — signatures are unchanged (constitution I /
spec FR-008); only the runtime guarantee changes.

## Contract 1 — Action completion (`ActionBase`, all 5 action types)

**Entry points**: `DBQueryAction`, `DBInsertAction`, `DBBatchAction`, `DBCallAction`,
`DBRawQueryAction` (`execute(session: Session): Unit`).

- **Precondition**: none (any `Session`, any `Expression[String]` for `requestName`,
  resolvable or not).
- **Postcondition**: exactly one of:
  - `next(session)` is invoked with an `OK`-reported session, or
  - `next(session.markAsFailed)` is invoked with a `KO`-reported session,
  in all cases exactly once, and exactly one stats entry (`logRequestCrash` or the normal
  response log) is emitted. No execution path terminates without calling `next`.
- **Failure routing**: unresolved `requestName`/`sql`/`params` → `crashOnFailure` → KO with a
  stable fallback name (never a silent no-op).

## Contract 2 — Check evaluation (`DBQueryAction` only — the sole action type with checks)

**Entry point**: `Check.check(result, session, checks.toList, ...)` inside
`DBQueryAction.execute`.

- **Precondition**: `checks: Seq[JdbcCheck]` may contain a predicate that throws.
- **Postcondition**: a thrown exception during check evaluation is caught and reported as a
  KO through the same path as a normal check failure (`Some("Check ERROR")` / exception
  message) — never propagates uncaught, never silently drops the `next` call.

## Contract 3 — Check composition (`QueryActionBuilder`, Scala and Java)

**Entry points**: Scala `actions.scala` `QueryActionBuilder.check(newChecks: JdbcCheck*)`;
Java `QueryActionBuilder.check(Object...)` / `check(List<Object>)`.

- **Scala**: `builder.check(a).check(b)` MUST produce `checks == existing ++ Seq(a) ++
  Seq(b)` (append, declaration order) — never a replacement of prior checks.
- **Java**: calling `.check(...)` on a `QueryActionBuilder` reference MUST return a **new**
  instance and MUST NOT mutate the receiver or any other `QueryActionBuilder` derived from
  the same base builder. Two branches derived from one base are independent: checks added to
  branch A MUST NOT appear on branch B, and vice versa, regardless of build order.
- **No signature change**: both methods keep their existing parameter and return types.

## Contract 4 — Batch execution order (`JDBCClient.batch`)

**Entry point**: `def batch[U](queries: Seq[SqlWithParam])(consumer: Try[Array[Int]] => U): Future[U]`.

- **Precondition**: `queries` is an ordered sequence of SQL+params, possibly interleaving
  distinct SQL texts and possibly repeating the same SQL text non-contiguously.
- **Postcondition**: the final database state after the batch commits is equivalent to
  executing every element of `queries` individually, in declared order. Statements with
  identical SQL text that are **contiguous** in `queries` MAY be merged into one
  `addBatch()`/`executeBatch()` group for efficiency; statements separated by a different SQL
  text MUST NOT be reordered relative to that different statement.
- **No signature change**: `batch`'s parameter and return types are unchanged; only internal
  grouping/ordering changes. Commit-all/rollback-all atomicity (constitution III) is
  preserved unchanged.

## Non-goals

- No new public methods, overloads, or DSL surface — this is a correctness-only contract
  restoration, not new API (spec FR-008, SC-005).
- No change to `DBInsertAction`, `DBCallAction`, `DBRawQueryAction`, `DBBatchAction` check
  semantics — they carry no `checks` field/method today and none is added by this feature.
