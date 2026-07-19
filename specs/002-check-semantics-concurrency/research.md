# Phase 0 Research: Check Semantics & Concurrency Correctness

No `[NEEDS CLARIFICATION]` markers remain in the spec or Technical Context — all 5 defects
are pinned to exact file/line locations by the milestone audit (baseline
`a8d0401bd92ea694f5f550dd279e61d5581408c3`) and confirmed against the current tree below.
This document records the fix *decision* for each defect (the "how"), not open unknowns.

## Scope confirmation (current tree, not baseline)

- `ActionBase.crashOnFailure` (`src/main/scala/org/galaxio/gatling/jdbc/actions/ActionBase.scala:28-34`)
  is called from **all five** action types: `DBQueryAction`, `DBInsertAction`,
  `DBBatchAction`, `DBCallAction`, `DBRawQueryAction` (each does
  `.onFailure(crashOnFailure(session, requestName))`). Fixing it once fixes #77 for every
  action type, satisfying the spec's "for every action type" acceptance intent.
- `Check.check(...)` is invoked in exactly **one** place: `DBQueryAction.scala:38`. No other
  action builder (`DBInsertActionBuilder`, `DBCallActionBuilder`, `BatchActionBuilder` /
  `DBBatchAction`) exposes a `.check(...)` method or a `checks` field in
  `actions.scala` — only `QueryActionBuilder` does. So #78's fix is scoped to
  `DBQueryAction` alone; there is no check-throwing path to guard in the other four action
  types because they have no user checks to throw.
- Of the Java action builders under `src/main/java/org/galaxio/gatling/javaapi/actions/`,
  only `QueryActionBuilder.java` holds a non-`final` wrapped field and mutates-in-place;
  `DBCallActionBuilder`, `DBInsertActionBuilder`, `RawSqlActionBuilder`, `BatchActionBuilder`,
  `DBCallActionParamStep` already use `private final` + return-a-new-instance. #80's fix is
  scoped to `QueryActionBuilder.java` alone.

## Decision: #77 — requestName EL resolution hang

- **Decision**: `crashOnFailure` stops re-invoking `requestName(session)` under `.map` (which
  silently no-ops when the re-resolution also fails). Resolve once via
  `requestName(session).toOption.getOrElse(name)` — `name` being the action's own stable
  Gatling action name, per issue #77's "stable action-name fallback" — and unconditionally
  call `logRequestCrash` + `executeNext`, so a KO is always emitted on this path.
- **Rationale**: The whole point of `crashOnFailure` is to run *specifically when*
  `requestName`/`sql`/`params` resolution failed. Re-resolving the same expression against
  the same session inside the handler and only acting on success is a self-defeating guard —
  it only "works" when resolution failure was transient, which it structurally cannot be for
  a pure `Expression[String]`. Using `.toOption.getOrElse(fallback)` instead of `.map` removes
  the dependency on a second successful resolution entirely.
- **Alternatives considered**: (a) Thread the already-resolved name from the calling
  `for`-comprehension into `crashOnFailure` — rejected because `onFailure` fires exactly when
  that resolution *didn't* succeed, so there is no resolved name to thread; a fallback string
  is unavoidable. (b) Swallow the crash silently and just call `executeNext` with a hardcoded
  constant — rejected, loses the diagnostic `logRequestCrash` call and the actual unresolved
  expression text that helps users debug a bad `#{attr}` reference.

## Decision: #78 — throwing JDBC checks hang the VU

- **Decision**: Wrap the `Check.check(result, session, checks.toList, ...)` call (and its
  match) in `DBQueryAction.scala:36-52` with a `NonFatal` guard that routes a thrown exception
  through the same KO path as `reportError` — same session-failed/KO/stats/`next` contract,
  with the exception's message as the failure reason.
- **Rationale**: This is a single call site (see scope confirmation above), so a local
  `Try`/`NonFatal` wrap is simpler and more idiomatic than introducing a shared "safe check"
  abstraction in `ActionBase` for a capability only one action type has (Idiomatic Simplicity —
  no premature abstraction for a one-caller concern).
- **Alternatives considered**: (a) Guard inside `JdbcCheckSupport.simpleCheck`'s `f(response)`
  call instead — rejected, it would only cover `simpleCheck`-built checks, not arbitrary
  `CheckBuilder`/`Check.Simple` checks composed other ways; the guard belongs at the single
  `Check.check(...)` call site so it covers every check shape. (b) Guard inside
  `JDBCClient.executeSelect`'s `Future { ... consumer(result) }` — rejected, that would also
  swallow genuine SQL/driver exceptions that currently correctly flow to
  `case Failure(exception) => reportError(...)`, conflating two different failure classes.

## Decision: #79 — Scala `.check()` replaces previous checks

- **Decision**: `actions.scala:49` changes from `this.copy(checks = newChecks)` to
  `this.copy(checks = checks ++ newChecks)`.
- **Rationale**: One-line append fix restores the documented chaining contract with zero
  signature change and zero risk to the rest of the builder.
- **Alternatives considered**: Deduplicating repeated identical checks on append — rejected,
  out of scope; the issue is about checks being *dropped*, not about duplicate checks, and
  de-duplication would be an undocumented behavior change nobody asked for.

## Decision: #80 — Java `QueryActionBuilder` mutates existing branches

- **Decision**: Make `wrapped` `private final`; `.check(List<Object>)` returns
  `new QueryActionBuilder(wrapped.check(...))` instead of assigning `this.wrapped` and
  returning `this`.
- **Rationale**: Matches the copy-on-write pattern every sibling Java action builder in the
  same package already uses (`DBCallActionBuilder`, `DBCallActionParamStep`, etc. — see scope
  confirmation). Restores branch independence with the smallest possible diff, consistent with
  how the Scala side (`actions.scala:49`, post-#79-fix) already behaves.
- **Alternatives considered**: Deep-copying `wrapped` inside a still-mutable field — rejected,
  unnecessary complexity; `wrapped` is itself immutable (`actions.scala` case class), so a new
  outer wrapper referencing the new inner value is sufficient and is exactly the sibling-builder
  pattern.

## Decision: #82 — Batch execution reorders non-contiguous SQL

- **Decision**: Replace `queries.groupBy(_.sql).toSeq` (unordered `Map`) in
  `JDBCClient.scala:163` with an order-preserving **contiguous-run** chunking: walk `queries`
  in original order and merge only *adjacent* elements sharing the same SQL text into one
  batch group; each group still executes via `addBatch()`/`executeBatch()`, but groups execute
  in the order their first element appeared in `queries`.
- **Rationale**: This is the fix the audit itself specifies verbatim — "Preserve original
  order; only group adjacent identical SQL if required" (#82). It keeps the JDBC batching
  efficiency win for genuinely adjacent repeated statements (e.g., three consecutive identical
  inserts) while guaranteeing `insert A -> update all -> insert B` executes in that exact
  sequence.
- **Alternatives considered**: (a) Drop `addBatch` grouping entirely, execute every statement
  individually — rejected, throws away the batching performance characteristic the `batch(...)`
  API exists for, a bigger behavior change than the defect requires. (b) Use a
  `LinkedHashMap[String, Vector[SqlWithParam]]` keyed by SQL to preserve *first-occurrence*
  order — rejected, still merges **non-contiguous** repeats of the same SQL text into one
  group (e.g. `[insertA, updateAll, insertA]` would still execute both `insertA`s together,
  ahead of `updateAll` if `insertA` was first), which reintroduces exactly the reordering the
  audit's failure scenario describes; only contiguous-run chunking preserves true declared
  order for non-contiguous identical statements.
