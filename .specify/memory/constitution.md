<!--
SYNC IMPACT REPORT
==================
Version change: 1.1.0 → 2.0.0
Bump rationale: MAJOR — release governance redefined to follow the backlog
constitution (galax-io/galaxio-sdlc-backlog, Principle I): this repository
holds no milestone, its pull requests carry none, and the release sets the
version. The rules removed contradicted that constitution, which ranks
above this one (backlog Precedence).

Modified principles:
- IV. Spec-Driven, Semantic Delivery — commit subjects drive the changelog,
  not the version; the milestone-before-merge rule removed.
Modified sections:
- Release Discipline — the version is set by the release (`vX.Y.0`, a patch
  `vX.Y.Z` on `release/X.Y.0`), not by commit types; the release-milestone and
  linkage-guard rule removed (the linkage gate no longer exists).
- Governance — the backlog constitution named first in the precedence.

Added sections: none
Removed sections: none

Templates alignment:
- .specify/templates/*.md  ✅ compatible — no template names milestones or
  the version rule.
- AGENTS.md                ⚠ "Commits & PRs" (Conventional Commits guide the
  version) and "Release Process → Rules" (version from commits) still say the
  old rule; they are fixed from the backlog with the shared AGENTS.md sections.
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
- 1 concern per PR; Conventional Commit subjects drive the changelog.
- This repository holds no milestone and its PRs carry none: a release is a business
  milestone of `galax-io/galaxio-sdlc-backlog`, and a PR of a feature links it with
  `Part of galax-io/galaxio-sdlc-backlog#<feature>`.

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
- The version comes from the tag (dynver) and is set by the release, not by commit types:
  the next minor `vX.Y.0` for a release of `main`, a patch `vX.Y.Z` on `release/X.Y.0` for
  a fix of a released version. A change shipped under the Principle I carve-out is a
  `fix` and never forces MAJOR by itself.
- Tags are allowed only on `main` or `release/*`; stray tags are rejected.
- A version number is NEVER reused and a published release tag is NEVER deleted.

## Governance

This constitution supersedes ad-hoc practice. When guidance conflicts, the order of
precedence is: the constitution of `galax-io/galaxio-sdlc-backlog`, this constitution, then
`AGENTS.md`, then other docs. A rule here that contradicts the backlog constitution is a
defect of this one, fixed from the backlog.

- Amendments MUST be made by editing this file, MUST state the version bump and rationale,
  and MUST propagate to dependent templates (`plan`, `spec`, `tasks`) in the same change.
- Versioning follows semantic versioning: MAJOR for backward-incompatible governance or
  principle removal/redefinition, MINOR for a new principle or materially expanded section,
  PATCH for clarifications and non-semantic refinements.
- Every PR and review MUST verify compliance with these principles; any added complexity
  MUST be justified against a simpler rejected alternative.
- `AGENTS.md` remains the day-to-day runtime development guide; it MUST stay consistent with
  this constitution, and a divergence is a defect to fix in the constitution or in `AGENTS.md`.

**Version**: 2.0.0 | **Ratified**: 2026-07-12 | **Last Amended**: 2026-11-27
