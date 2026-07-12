<!--
SYNC IMPACT REPORT
==================
Version change: (uninitialized template) → 1.0.0
Bump rationale: Initial ratification. The file previously contained only unfilled
placeholder tokens; this is the first concrete, adopted constitution (MAJOR baseline).

Modified principles (placeholder → concrete):
- [PRINCIPLE_1_NAME]  → I. Backward Compatibility (NON-NEGOTIABLE)
- [PRINCIPLE_2_NAME]  → II. Test-First With Real Databases
- [PRINCIPLE_3_NAME]  → III. Resource & Concurrency Safety
- [PRINCIPLE_4_NAME]  → IV. Spec-Driven, Semantic Delivery
- [PRINCIPLE_5_NAME]  → V. Idiomatic Simplicity

Added sections:
- Build, Format & Dependency Discipline (was [SECTION_2_NAME])
- Release Discipline (was [SECTION_3_NAME])
- Governance (concrete rules)

Removed sections: none

Templates alignment:
- .specify/templates/plan-template.md      ✅ compatible — "Constitution Check" uses a
  dynamic per-feature gate ("[Gates determined based on constitution file]"); no hardcode
  needed. Its gate is satisfied by principles I–V.
- .specify/templates/spec-template.md       ✅ compatible — no principle conflict; mandatory
  sections unchanged by this constitution.
- .specify/templates/tasks-template.md      ✅ compatible — TDD-first ordering and
  "commit after each task" already match Principles II and IV.
- .specify/templates/commands/*.md          ✅ n/a — directory absent; no agent-specific
  references to reconcile.
- AGENTS.md (runtime guidance)              ✅ source of truth for these principles; no edit.

Follow-up TODOs: none. RATIFICATION_DATE set to first-adoption date (2026-07-12).
-->

# Gatling JDBC Plugin Constitution

## Core Principles

### I. Backward Compatibility (NON-NEGOTIABLE)

The published Scala DSL, the Java/Kotlin `javaapi` facade, protocol defaults, and any
serialized or observable behavior are a public contract with downstream load tests.
Changes MUST preserve existing signatures, default values, and runtime semantics.

- A breaking change to a published API, default, or serialized format REQUIRES a MAJOR
  version bump and an explicit, written justification in the PR.
- Additive evolution (new overloads, new optional parameters with safe defaults) is the
  default path; deprecate before removing.
- Treat Scala DSL, Java builders, defaults, and plugin semantics as compatibility-sensitive
  by default — when in doubt, assume a consumer depends on it.

**Rationale**: This plugin is consumed by external performance suites that cannot be
refactored in lockstep; silent behavior drift corrupts load-test results and erodes trust.

### II. Test-First With Real Databases

Behavior changes MUST be covered by focused, automated tests, and those tests MUST exercise
a real JDBC path rather than a mock wherever a real path exists.

- Follow TDD where practical: add or update the regression test with the behavior change in
  the same commit.
- Validate JDBC/Gatling behavior against H2, or PostgreSQL via Testcontainers — do NOT mock
  a database, driver, or connection pool when a real integration path is available.
- Every commit MUST be green on the default gate: `sbt scalafmtCheckAll scalafmtSbtCheck
  compile test`.
- `DebugTest` (the Gatling JDBC example on H2) MUST keep passing.

**Rationale**: JDBC semantics are driver- and pool-dependent; mocks hide the exact
timeout, transaction, and resource behavior that this plugin exists to exercise.

### III. Resource & Concurrency Safety

JDBC interactions are blocking and driver-dependent. Any change touching `db/`, resource
lifecycle, protocol wiring, or action execution MUST be reviewed for correctness under load.

- Every connection, statement, and result set MUST be released on all paths, including
  error and timeout paths — no leaks.
- Pool sizing, transaction boundaries, timeout handling, error propagation, and thread
  behavior MUST be explicitly considered and stated in the PR for such changes.
- Blocking work MUST NOT starve or deadlock Gatling's execution threads; backpressure from
  the connection pool MUST be handled without deadlock.

**Rationale**: Under load, a single leaked resource or mishandled blocking call degrades
both correctness and the observability the plugin is measuring.

### IV. Spec-Driven, Semantic Delivery

Work is planned as specs and delivered as small, semantic, traceable units.

- Spec-first: `specs/NNN-<feature>/` artifacts land in their own `docs(speckit): …` commit
  BEFORE any `feat`/`fix`; spec artifacts are never folded into implementation commits.
- 1 tracked issue = 1 Conventional Commit (`type(scope): … (#NNN)`), green on its own.
- 1 concern per PR; Conventional Commit subjects drive the changelog and the version
  (`feat` → minor, `!`/`BREAKING CHANGE` → major, else patch).
- Every PR and every issue it closes MUST be tied to the active milestone before merge;
  no milestone = do not merge.

**Rationale**: Traceable, single-concern history makes the automated changelog and
tag-driven release trustworthy and keeps review scoped.

### V. Idiomatic Simplicity

Prefer the smallest clear change that respects the language and the surrounding code.

- Follow Scala/Java/Kotlin idioms and conventions already present in the codebase.
- Apply KISS and DRY; apply SOLID only where it improves clarity or testability; avoid
  premature abstraction in public APIs.
- No control-flow-by-exception, no dead code, no duplicated code, and no opportunistic
  refactors outside the stated scope of a change.

**Rationale**: A load-testing plugin must stay readable and predictable; cleverness that
obscures blocking or resource behavior is a liability, not an asset.

## Build, Format & Dependency Discipline

- Sources MUST be scalafmt-clean before commit (`sbt scalafmtAll scalafmtSbt`); the format
  gate (`sbt scalafmtCheckAll scalafmtSbtCheck`) MUST pass before push.
- `build.sbt`, `project/Dependencies.scala`, and `project/plugins.sbt` are the single source
  of truth for build and dependency behavior; `.github/workflows/` is the source of truth
  for CI, formatting, compile, test, coverage, and release behavior.
- New dependencies, dependency upgrades, and public API signature changes REQUIRE explicit
  approval before they land.
- Baseline toolchain: Scala 2.13.18, Java 17+, Gatling 3.13.5, HikariCP pooling. Changing a
  baseline version is a deliberate, approved decision, not an incidental edit.
- Branch from `main`; never force-push or commit directly to `main`; rebase (never merge)
  within PR branches.

## Release Discipline

- Trunk-based with `release/*` branches cut from `main`. Releases are manual and tag-driven:
  CI only tests; `release.yml` runs only on `v*` tags.
- The version comes from the tag (dynver) and MUST be chosen from the Conventional Commits
  since the last tag (`feat` → minor, `!`/`BREAKING CHANGE` → major, else patch).
- Tags are allowed only on `main` or `release/*`; stray tags are rejected.
- A version number is NEVER reused and a published release tag is NEVER deleted.
- A release milestone is tag-ready only when every issue in it is closed and every PR merged;
  the linkage guard gates the release tag until the milestone passes.

## Governance

This constitution supersedes ad-hoc practice. When guidance conflicts, the order of
precedence is: this constitution, then `AGENTS.md`, then other docs.

- Amendments MUST be made by editing this file, MUST state the version bump and rationale,
  and MUST propagate to dependent templates (`plan`, `spec`, `tasks`) in the same change.
- Versioning follows semantic versioning: MAJOR for backward-incompatible governance or
  principle removal/redefinition, MINOR for a new principle or materially expanded section,
  PATCH for clarifications and non-semantic refinements.
- Every PR and review MUST verify compliance with these principles; any added complexity
  MUST be justified against a simpler rejected alternative.
- `AGENTS.md` remains the day-to-day runtime development guide; it MUST stay consistent with
  this constitution, and a divergence is a defect to fix in the constitution or in `AGENTS.md`.

**Version**: 1.0.0 | **Ratified**: 2026-07-12 | **Last Amended**: 2026-07-12
