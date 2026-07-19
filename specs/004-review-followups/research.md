# Phase 0 Research: Post-Review Follow-Ups (004)

All Technical Context entries were known from the v1.3.0 review session; no NEEDS CLARIFICATION markers existed. Research below pins the five design decisions. House style: rationale first, then decision.

## R1. Which assertion is "the weak one", and how to strengthen it (FR-004)

**Rationale**: A replace-instead-of-append regression in the Scala builder keeps only the *last* `.check(...)` call's checks. The third test in `QueryActionBuilderCheckChainSpec` ("report a failure when any of three chained checks fails") registers `passing → failingMid → failingLast`. Under the regression the surviving check is `failingLast`, the session still fails, KO count is still 1 — the test passes and therefore cannot detect the regression alone. The first (builder-level) and second (behavior-level, `failingFirst → passingSecond`) tests do detect it; the third is the reviewer-flagged weak sibling. A message-content assertion cannot discriminate which failing check fired because both use the same `simpleCheck(_ => false)` failure text.

**Decision**: Rearrange the third test to `passing → failingMid → passingLast` (last registered check passes). Under the regression the surviving check passes: no KO, session not failed — the existing assertions (`isFailed shouldBe true`, KO size 1) then fail, detecting the regression standalone. Add the `responseCode shouldBe Some("Check ERROR")` assertion for parity with the second test. Test name and claim ("any of three chained checks fails") remain true — the failing check is now mid-chain.

**Alternatives considered**: (a) assert on failure-message content to pin which check fired — rejected: both failing checks emit identical text, no discrimination; (b) add a fourth test instead of touching the third — rejected: leaves the weak test weak and grows the suite for no added claim; (c) count executed checks via instrumented predicates — rejected: over-engineering for a regression probe that arrangement alone fixes.

## R2. Shape of the consolidated KO path in `DBQueryAction` (FR-003)

**Rationale**: The two call sites (`Some(validation.Failure(errorMessage))` branch and `catch NonFatal(e)`) differ in exactly two inputs: which session gets `markAsFailed` (`newSession` — the session after check evaluation — vs the original `session`, because when `Check.check` itself throws, `newSession` was never bound) and the message source (`errorMessage` vs `e.getMessage`). Everything else (`startTime`, `received`, `KO`, `next`, `resolvedName`, `Some("Check ERROR")`) is identical and already in scope inside the `Success(result)` closure. A nested `def` captures that scope with zero API growth; a case-class-level or `ActionBase`-level helper would need 5+ threaded parameters or would widen a shared trait for a path only `DBQueryAction` has (`DBCallAction` and the other three actions carry no checks — verified, no `Check.check` call site exists outside `DBQueryAction`).

**Decision**: Inside the `Success(result)` branch, introduce a local `def failCheck(failedSession: Session, message: String): Unit = executeNext(failedSession.markAsFailed, startTime, received, KO, next, resolvedName, Some("Check ERROR"), Some(message))`; both branches delegate: `failCheck(newSession, errorMessage)` and `failCheck(session, e.getMessage)`. Per-mode session choice and message source preserved exactly; the existing `(#78)` comment stays.

**Alternatives considered**: (a) protected helper on `ActionBase` — rejected: grows a shared surface for a single-action concern (Constitution V: no premature abstraction); (b) private method on the case class — rejected: needs `startTime`/`received`/`resolvedName`/`next` threaded through parameters, noisier than the duplication it removes; (c) leave as-is — rejected: reviewer-confirmed duplication, and Constitution V says no duplicated code.

## R3. Where the Java builder upgrade note lands (FR-001)

**Rationale**: Users hit the copy-on-write change in two moments: when reading what changed in v1.3.0 (release notes — already published 2026-07-19, currently a pure changelog with no guidance) and when writing Java checks (README `## Checks` → `### Java`, the section every Java example funnels through). The silent-failure mode (ignored return value → checks never registered) needs to be visible at both. The release is published, so the note there is an amendment, not a new artifact.

**Decision**: Two placements, one concern: (1) README `## Checks` → `### Java` gains a short warning block — `check(...)` returns a **new** builder since 1.3.0, reassign or chain it; before/after example; original instance stays valid for branching (that is the feature). (2) The v1.3.0 GitHub release notes gain an appended `### Upgrade notes` section with the same content, via append-only `gh release edit` executed as its own confirmed step at implementation time.

**Alternatives considered**: (a) README only — rejected: upgraders read release notes, not README diffs; (b) a CHANGELOG.md file — rejected: repo has none; git-cliff generates release notes, and a hand-edited parallel changelog would drift; (c) wait for v1.3.1 notes — rejected: the note concerns v1.3.0 behavior; readers of v1.3.0 notes must see it.

## R4. Where the batch ordering/grouping rule lands (FR-002)

**Rationale**: `README ### Batch Operations` is the only user-facing batch documentation and already carries the Scala/Java/Kotlin examples; scaladoc on `JDBCClient.contiguousSqlRuns` is invisible to Java/Kotlin users and to anyone reading the site render. The rule is short: order preserved; adjacent identical statements share one execution group; interleaving identical statements increases group count (A,B,A → 3 groups; A,A,B → 2); atomicity/rollback unchanged.

**Decision**: Add an "Execution order and grouping" paragraph to `### Batch Operations` stating the three rules, the A,B,A vs A,A,B example, the guidance (place identical statements adjacently when order allows and fewer groups are desired), and an explicit "transactional behavior unchanged" sentence (spec edge case).

**Alternatives considered**: (a) scaladoc only — rejected: wrong audience reach; (b) new top-level README section — rejected: fragments batch docs across two anchors.

## R5. Issue and milestone wiring (Constitution IV)

**Rationale**: The constitution gates merges on "1 tracked issue = 1 Conventional Commit" and "no milestone = do not merge". The four items have no tracked issues yet — they originate from a post-merge review conversation, not from filed issues. v1.3.0's milestone is closed; the next patch milestone does not exist yet.

**Decision**: Before implementation commits land, create four issues (one per item, each self-contained with the review finding) and a `v1.3.1` milestone; tie issues and the eventual PR to it. Issue creation is a repo mutation — performed during implementation with explicit confirmation, not during planning.

**Alternatives considered**: (a) one umbrella issue for all four — rejected: breaks 1-issue-1-commit traceability; (b) skip issues since items are small — rejected: constitution gate is explicit.

## R6. Scope boundary confirmation (FR-003 "query action" only)

**Rationale**: Reviewed whether the duplication pattern exists elsewhere: `DBCallAction` has no checks parameter and no `Check.check` call; `DBInsertAction`, `DBBatchAction`, `DBRawQueryAction` likewise. The only `Check.check` call site in `src/main` is `DBQueryAction`.

**Decision**: Consolidation touches `DBQueryAction` only; no cross-action abstraction is warranted (and Constitution V forbids the premature version).

**Alternatives considered**: shared check-execution helper across actions — rejected: single consumer today; abstract when a second appears.
