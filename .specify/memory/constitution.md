<!--
SYNC IMPACT REPORT
==================
Version change: 1.0.0 → 1.1.0
Bump rationale: MINOR — Principle I materially expanded with a bounded
"corruption & injection carve-out" permitting defect-driven runtime-behavior
changes in MINOR releases under four explicit conditions. No principle removed
or redefined; the default (behavior changes ⇒ MAJOR) stands.

Modified principles:
- I. Backward Compatibility (NON-NEGOTIABLE) — unchanged title; added carve-out
  bullet codifying the maintainer decision of 2026-07-22 (milestone 8, spec
  005-injection-secrets-null: #93 NULL-sentinel removal, #125 EL-in-WHERE
  rejection ship as v1.5.0 minor).

Added sections: none
Removed sections: none

Templates alignment:
- .specify/templates/plan-template.md   ✅ compatible — "Constitution Check" is a
  dynamic per-feature gate; carve-out is evaluated per feature, no hardcode.
- .specify/templates/spec-template.md   ✅ compatible — no mandatory-section change.
- .specify/templates/tasks-template.md  ✅ compatible — TDD-first ordering already
  satisfies carve-out condition (d).
- specs/005-injection-secrets-null/plan.md ✅ updated in the same change —
  Constitution Check row I and Complexity Tracking now cite the carve-out.
- AGENTS.md (runtime guidance)          ✅ consistent — its "preserve backward
  compat" boundary remains the default; the constitution supersedes on the
  bounded exception (see Governance precedence). No AGENTS.md edit required.

Follow-up TODOs: none.
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
- **Corruption & injection carve-out**: a runtime-behavior change whose sole effect is to
  stop silent data corruption, secret/PII disclosure, or execution of unintended SQL MAY
  ship in a MINOR release as a plain `fix` commit (no `!`/`BREAKING CHANGE` marker),
  provided ALL of the following hold:
  (a) the defect makes current behavior misrepresent or damage what the user specified;
  (b) an explicit written justification lands in the feature plan (Complexity Tracking)
      and in the PR body;
  (c) a migration note naming the replacement path ships in the same release's release
      notes;
  (d) a regression test pins the corrected behavior in the same commit.
  Bug-for-bug compatibility is not a published contract. The carve-out NEVER covers
  signature or API removals — those remain MAJOR-only.
  *Precedent*: milestone 8 / spec `005-injection-secrets-null` (#93 `"NULL"`-sentinel
  removal, #125 EL-in-`where` rejection), decided 2026-07-22, shipped as v1.5.0.

**Rationale**: This plugin is consumed by external performance suites that cannot be
refactored in lockstep; silent behavior drift corrupts load-test results and erodes trust.
The carve-out exists because preserving a corrupting or injectable behavior in the name of
compatibility protects the defect, not the consumer — the same trust argument, inverted.

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
  since the last tag (`feat` → minor, `!`/`BREAKING CHANGE` → major, else patch); a change
  shipped under the Principle I carve-out is a `fix` and never forces MAJOR by itself.
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

**Version**: 1.1.0 | **Ratified**: 2026-07-12 | **Last Amended**: 2026-07-22
