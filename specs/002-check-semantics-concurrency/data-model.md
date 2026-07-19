# Phase 1 Data Model: Check Semantics & Concurrency Correctness

This feature fixes broken invariants on **existing** entities; it introduces no new
persisted data, database tables, or DTOs. This document states the invariant each entity
MUST hold after the fix, since that invariant — not a schema — is what's being restored.

## JDBC Action

**Represents**: one Gatling action invocation against the database (query, insert, batch,
raw SQL, or stored procedure). Defined per action type in
`src/main/scala/org/galaxio/gatling/jdbc/actions/` (`DBQueryAction`, `DBInsertAction`,
`DBBatchAction`, `DBCallAction`, `DBRawQueryAction`), all extending `ActionBase`.

**Fields** (conceptual, not new code):
- `requestName: Expression[String]` — session-resolved display/stats name.
- outcome: exactly one of `OK` / `KO`, reported exactly once via `executeNext`.

**Invariant restored (#77)**: regardless of whether `requestName`, `sql`, or `params`
resolution fails, or the underlying DB call fails, or (for query actions) a check throws —
the action produces **exactly one** `executeNext` call (one KO or one OK) and **exactly one**
`next(session)` invocation. No path may leave the action's `Future`/`Validation` chain
without calling `executeNext`.

**State transition**: `Pending -> {OK, KO}` — terminal, single transition, no `Pending ->
Pending` (hang) state reachable from any input.

## Check

**Represents**: a user-declared assertion attached to a query action —
`org.galaxio.gatling.jdbc.internal.JdbcCheck` (Scala) built via `JdbcCheckSupport` (e.g.
`simpleCheck`), or the Java-facing `Object` checks converted via
`JdbcCheck.toScalaChecks` in the `javaapi` facade.

**Fields**:
- `checks: Seq[JdbcCheck]` on `QueryActionBuilder` (Scala, `actions.scala:47`) and the
  Java `QueryActionBuilder`'s `wrapped` field (`QueryActionBuilder.java:8`).

**Invariant restored (#79, #80)**:
- Scala: `builder.check(a).check(b)` yields a builder whose `checks` is the
  concatenation `existingChecks ++ newChecks`, in call order — never a replacement.
- Java: `builder.check(a)` returns a **new** `QueryActionBuilder` instance; the original
  `builder` reference and any other `QueryActionBuilder` derived from the same base **MUST**
  remain unaffected by later `.check(...)` calls on a sibling branch.

**Invariant restored (#78)**: evaluating `checks` against a query result (`Check.check(...)`
in `DBQueryAction`) MUST NOT let an exception thrown by a check predicate escape uncaught;
a thrown exception is treated as a check failure and routed through the same KO path as a
normal check failure or a `reportError` call — never silently dropped, never left unhandled
inside the enclosing `Future`.

## Batch Operation Sequence

**Represents**: an ordered list of `SqlWithParam` (SQL text + parameter map) submitted as
one `JDBCClient.batch(...)` call (`src/main/scala/org/galaxio/gatling/jdbc/db/JDBCClient.scala:160-185`).

**Fields**:
- `queries: Seq[SqlWithParam]` — the declared, ordered input.
- per-statement result: `Array[Int]` (JDBC update counts from `executeBatch()`).

**Invariant restored (#82)**: the **relative execution order of distinct SQL statements**
in `queries` MUST be preserved. Only *contiguous* runs of identical SQL text may be merged
into a single `addBatch()`/`executeBatch()` group (an efficiency optimization); a group
boundary MUST NOT cause two occurrences of the same SQL text that are separated by a
different statement to be reordered relative to that different statement. Final database
state after the whole batch executes MUST match executing `queries` one at a time, in
declared order. Commit/rollback (`JDBCClient.scala:179-182`, via `withConnectionForBatch`)
remains atomic across the whole batch — this invariant does not change.
