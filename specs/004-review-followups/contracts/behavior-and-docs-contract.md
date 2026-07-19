# Contract: Behavior Freeze & Documentation Content (004)

Three contracts. The runtime contract freezes what already ships; the documentation contracts define required content. No new public API is introduced anywhere in this feature.

## C1. Check-failure reporting contract (FR-003, freeze)

Given a query action with checks, when a check fails, the plugin MUST report:

| Failure mode | Session marked failed | Status | Response code | Message | Next step |
|--------------|----------------------|--------|---------------|---------|-----------|
| Check evaluates to failure | the post-check session | KO | `Check ERROR` | the check's failure message | invoked exactly once |
| Check raises an error | the original session | KO | `Check ERROR` | the raised error's message | invoked exactly once |

- Both rows MUST be produced by a single shared reporting path (one call site constructs the KO report).
- Timing fields (start, received) MUST be captured before check evaluation, as today.
- A passing check's OK path is out of scope and MUST NOT change.
- **Verification**: existing suites pass unmodified — `ThrowingCheckSpec` (raised-error row), `QueryActionBuilderCheckChainSpec`/`SessionMarkAsFailedSpec` (returned-failure row), `ActionSessionFailureSpec` (continuation); plus single-call-site inspection.

## C2. Upgrade-note content contract (FR-001)

Both placements (README `## Checks`→`### Java`; v1.3.0 release notes `### Upgrade notes`) MUST contain:

1. The change: `check(...)` on the Java query builder returns a **new** builder since 1.3.0 (previously mutated in place).
2. The consequence: ignoring the returned builder registers no checks — the load test silently stops asserting.
3. A correct usage example, minimally:

   ```java
   // before 1.3.0 (no longer registers checks):
   builder.check(simpleCheck(simpleCheckType.NonEmpty));
   // since 1.3.0 — use the returned builder:
   builder = builder.check(simpleCheck(simpleCheckType.NonEmpty));
   // or chain directly:
   jdbc("q").query("...").check(simpleCheck(simpleCheckType.NonEmpty));
   ```

4. The reassurance: the original builder instance stays valid and unchanged — reuse it to branch independent scenarios.

- Release-notes placement is **append-only**: existing v1.3.0 sections MUST NOT be edited or reordered.
- **Verification**: content review against this list; example mirrored by existing behavior tests (`QueryActionBuilderBranchSpec` proves both the loss-when-ignored and branch-independence claims).

## C3. Batch-documentation content contract (FR-002)

The `### Batch Operations` README section MUST state:

1. Declared statement order is always preserved (since 1.3.0).
2. Identical statements share one execution group only when adjacent.
3. Interleaving identical statements yields more groups — with the example A,B,A → 3 groups vs A,A,B → 2 groups.
4. Guidance: place identical statements adjacently when ordering allows and fewer groups are desired.
5. Transactional behavior (whole-batch commit/rollback) is unchanged by grouping.

- MUST NOT promise any performance number; the contract is group-count predictability, not throughput.
- **Verification**: content review against this list; group-count claims mirrored by `BatchOrderSpec`.
