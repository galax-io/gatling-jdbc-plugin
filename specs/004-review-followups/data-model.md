# Data Model: Post-Review Follow-Ups (004)

No persistent data entities. The feature's "data" is conceptual: one runtime report shape whose invariants must not drift, and two documentation artifacts with required content. Modeled here so contracts and validation can reference them.

## CheckFailureReport *(runtime, invariant-frozen — FR-003)*

The observable outcome the consolidated path must keep byte-identical for both failure modes.

| Field | Value | Source |
|-------|-------|--------|
| status | KO | fixed |
| response code | `"Check ERROR"` | fixed |
| message | returned-failure mode: the check's failure message; raised-error mode: the raised error's message | per-mode, preserved exactly |
| session marking | `markAsFailed` applied to: returned-failure mode — the post-check session; raised-error mode — the original session (post-check session never existed) | per-mode, preserved exactly |
| timing | start = action start; end = result received (both captured before check evaluation) | unchanged |
| continuation | next step invoked exactly once, after reporting | unchanged |

**State transition**: `result received → check evaluated → (pass: OK report) | (fail-by-result: CheckFailureReport) | (fail-by-throw: CheckFailureReport)` — three exits, two of which now share one reporting path.

## BatchExecutionGroup *(documented concept — FR-002)*

| Attribute | Rule |
|-----------|------|
| identity | maximal run of *adjacent* statements with identical SQL text |
| order | groups execute in declared statement order; statements within a group keep declared order |
| count | interleaving identical statements increases group count (A,B,A → 3; A,A,B → 2) |
| atomicity | unchanged by grouping — whole batch commits or rolls back as before |

## UpgradeNote *(documentation artifact — FR-001)*

Required content elements (all three mandatory):

1. **Change**: Java `QueryActionBuilder.check` returns a new builder since 1.3.0; no in-place mutation.
2. **Consequence**: calling it and ignoring the returned value registers nothing — checks silently lost.
3. **Correct pattern**: before/after example (reassign or chain); note the original instance remains valid and unchanged (branching is the feature).

Placements: README `## Checks` → `### Java`; v1.3.0 release notes `### Upgrade notes` (appended).

## RegressionProbe *(test arrangement — FR-004)*

| Attribute | Value |
|-----------|-------|
| suite | `QueryActionBuilderCheckChainSpec`, third test |
| arrangement | `passing → failingMid → passingLast` (last registered check must pass) |
| detection property | under replace-instead-of-append, surviving check = last = passing → no KO, session not failed → assertions fail standalone |
| assertions | `capturedSession.isFailed shouldBe true`; KO responses size 1; `responseCode shouldBe Some("Check ERROR")` |
