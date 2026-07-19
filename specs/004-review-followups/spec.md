# Feature Specification: Post-Review Follow-Ups from the v1.3.0 Milestone Review

**Feature Branch**: `004-review-followups`

**Created**: 2026-07-19

**Status**: Draft

**Input**: User description: "1-4 fix" — items 1–4 of the post-merge code review of milestone v1.3.0 (2026-07-19): (1) consolidate the duplicated check-failure reporting path, (2) publish an upgrade note for the Java builder behavior change, (3) document the batch grouping/ordering trade-off, (4) strengthen the weak check-chaining regression assertion. Item 5 of that review (PR process guidance) is out of scope.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Upgrading Java users are warned about the builder behavior change (Priority: P1)

A load-test author uses the Java API and upgrades to v1.3.0. In v1.3.0 the query builder's check-registration call returns a new builder instance instead of mutating the builder in place (the fix for shared-branch corruption). Code written against the old behavior — calling the method and ignoring the returned value — now silently registers no checks, so their load test keeps passing while asserting nothing. Upgrade documentation must state the change, its consequence, and the correct pattern so authors adapt before losing assertions.

**Why this priority**: The only follow-up with silent-failure risk for downstream users. A load test that silently loses its assertions reports false confidence — worse than a loud break.

**Independent Test**: Read the published v1.3.0 upgrade/release documentation; verify the behavior change, the consequence of ignoring the returned builder, and a correct before/after usage example are present and match actual runtime behavior.

**Acceptance Scenarios**:

1. **Given** the v1.3.0 upgrade documentation, **When** a Java API user reads it, **Then** it states that check registration returns a new builder, that ignoring the returned value means the checks are not registered, and shows a correct usage example.
2. **Given** the documented correct example, **When** a user follows it, **Then** all registered checks execute at runtime (the example is consistent with actual behavior).

---

### User Story 2 - Batch authors can predict grouping and ordering (Priority: P2)

A scenario author sends a batch containing interleaved identical statements (for example A, B, A). Since v1.3.0 the declared order is always preserved, and identical statements are merged into one execution group only when adjacent — so interleaved batches produce more execution groups than the pre-1.3.0 merge-everything behavior. Documentation must state this rule so authors can order statements deliberately when they want fewer groups.

**Why this priority**: User-visible performance characteristic of an intentional correctness trade-off. Undocumented, it surfaces as a confusing performance regression report; documented, it is a one-line authoring guideline.

**Independent Test**: Read the batch documentation; verify it states the order-preservation rule, the adjacency-merge rule, and the implication for interleaved batches, with guidance to place identical statements adjacently when ordering allows.

**Acceptance Scenarios**:

1. **Given** the batch execution documentation, **When** an author reads it, **Then** they can determine for any statement sequence how many execution groups it produces and in what order (for example: A,B,A → three groups; A,A,B → two groups).
2. **Given** the documentation, **When** an author needs fewer execution groups, **Then** the stated guidance (group identical statements adjacently) achieves it without changing observable results order.

---

### User Story 3 - Maintainers evolve check-failure reporting in one place (Priority: P3)

The query action reports a failed check through two structurally identical paths: one when a check evaluates to a failure result, one when a check raises an error. A future change to how check failures are reported (message format, failure marking, timing) applied to one path can silently miss the other, making the two failure modes drift apart. The reporting is consolidated into a single shared path with no change in observable behavior.

**Why this priority**: Internal maintainability only; no user-visible change. Prevents future divergence rather than fixing a present defect.

**Independent Test**: Inspection confirms a single reporting path serves both failure modes; the full existing regression suite passes unmodified, demonstrating unchanged observable behavior.

**Acceptance Scenarios**:

1. **Given** the consolidated reporting path, **When** a check fails by returning a failure, **Then** the reported outcome (status, failure marking on the virtual user, message content, continuation to the next step) is identical to pre-change behavior.
2. **Given** the consolidated reporting path, **When** a check fails by raising an error, **Then** the reported outcome is identical to pre-change behavior, including the raised error's message.
3. **Given** the consolidation, **When** the existing regression suites run, **Then** all pass without modification.

---

### User Story 4 - Check-chaining regression coverage stands on its own (Priority: P4)

The regression suite guarding chained check registration contains one supplementary assertion weaker than its siblings: if the sibling assertions were later refactored or removed, the weak assertion alone might not detect the regression it guards (chained registration silently replacing earlier checks instead of appending). The assertion is strengthened so it detects the regression independently.

**Why this priority**: Defense-in-depth for test quality; the regression is currently still caught by sibling assertions.

**Independent Test**: Reintroduce the replace-instead-of-append regression locally; the strengthened assertion must fail on its own. Restore the fix; it must pass.

**Acceptance Scenarios**:

1. **Given** the strengthened assertion, **When** chained check registration regresses to replacing earlier checks, **Then** that assertion fails even if sibling assertions are absent.
2. **Given** the current correct behavior, **When** the suite runs, **Then** the strengthened assertion passes.

---

### Edge Cases

- Java authors who keep and reuse the old builder reference after registering checks: documentation must make clear the original instance is unchanged (that is the branching feature), not stale or invalid.
- A batch whose identical statements are already all adjacent: grouping is identical to pre-1.3.0 behavior — documentation must not overstate the performance impact.
- A batch of entirely distinct statements: one group per statement, unchanged from before — no documentation-implied regression.
- The two check-failure modes carry messages from different sources (returned failure message vs raised error message); consolidation must preserve each mode's message content exactly.
- Batch documentation must not imply any change to transactional behavior: atomicity and rollback semantics are unchanged by grouping.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The v1.3.0 upgrade/release documentation MUST describe the Java query builder check-registration change (returns a new builder; no in-place mutation), the consequence of ignoring the returned value (checks silently not registered), and a correct usage example. *(review item 2)*
- **FR-002**: The batch execution documentation MUST state that declared statement order is preserved, that identical statements form one execution group only when adjacent, and that interleaving identical statements yields more execution groups than adjacent grouping — with guidance to order identical statements adjacently when fewer groups are desired. *(review item 3)*
- **FR-003**: The query action's check-failure reporting MUST be expressed as one shared path used by both failure modes (check returns a failure; check raises an error) with no observable behavior change: same status, same failure marking, same per-mode message content, same continuation to the next step. *(review item 1)*
- **FR-004**: The check-chaining regression suite MUST detect a replace-instead-of-append regression through the strengthened assertion alone, without relying on sibling assertions. *(review item 4)*
- **FR-005**: All existing automated tests MUST pass unmodified (except the single strengthened assertion of FR-004), demonstrating that no observable behavior changed.
- **FR-006**: Documented examples (FR-001, FR-002) MUST be consistent with actual runtime behavior — each example either executable as written or mirrored by an existing automated test.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Java API user following the documented upgrade pattern retains 100% of their registered checks after migrating to v1.3.0 — zero silent check loss when the documentation is applied.
- **SC-002**: A reader of the batch documentation can correctly predict the execution-group count and order for any given statement sequence (verifiable with sequences such as A,B,A → 3 and A,A,B → 2) without consulting source code.
- **SC-003**: Check-failure reporting logic exists in exactly one place; both failure modes produce reports byte-identical in content to v1.3.0 behavior, evidenced by the existing regression suites passing with zero modified assertions (other than FR-004's).
- **SC-004**: Reintroducing the check-append regression is caught by the strengthened assertion in isolation (verified by temporarily reverting the fix during development).
- **SC-005**: No public API signature, default value, or observable runtime semantic changes; the release containing this work qualifies as a patch-level change.

## Assumptions

- Scope is exactly items 1–4 of the 2026-07-19 post-merge review of milestone v1.3.0; item 5 (one-concern-per-PR process guidance) is process, not deliverable work, and is out of scope.
- The upgrade note (FR-001) lands in the repository's release documentation for v1.3.0 (release notes and/or the user-facing README/upgrade section); amending already-published v1.3.0 release notes is in scope if that is where users will look.
- The consolidation (FR-003) is internal-only; the public contract (Constitution Principle I: Backward Compatibility) is untouched, and behavior preservation is demonstrated by the existing real-database regression suites (Principle II) rather than by new mocks.
- The "weak assertion" (FR-004) is the supplementary assertion in the check-chaining regression suite identified during the 2026-07-19 review; the exact assertion is pinned during planning.
- Each review item is delivered as its own small, traceable unit per Constitution Principle IV (spec-first commit, then one semantic commit per item).
