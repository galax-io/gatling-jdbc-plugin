# gatling-jdbc-plugin — Agent Guide

JDBC plugin for Gatling — query, insert, batch, raw SQL, and stored-procedure load testing.

> Sections above the `---` are **project-specific** — the concrete facts of this repo.
> Everything below the `---` is the reusable development process (galax-io convention),
> adapted to this repo's actual CI/release tooling.

## Role

- Act as a Principal Engineer in software development and performance testing.
- Bring strong Scala, Java, Kotlin, Gatling plugin, and JDBC expertise.
- Prefer small, clear, backward-compatible changes unless the task explicitly requires otherwise.

## Stack

- Scala 2.13.18 core on sbt; Gatling 3.13.5; Java 17+ (Temurin in CI).
- JDBC plugin covering query, insert, batch, raw SQL, and stored-procedure flows.
- Java API facade (`javaapi`) with Kotlin-compatible usage and tests.
- HikariCP pooling; tests on H2 and on PostgreSQL via Testcontainers; ScalaTest.
- GitHub Actions CI, Scala Steward, Codecov, Sonatype publish via sbt-ci-release.

## Commands

```bash
sbt scalafmtAll scalafmtSbt                       # format — run before every push
sbt scalafmtCheckAll scalafmtSbtCheck             # format gate — must pass before push
sbt clean compile                                 # compile
sbt test                                          # ScalaTest unit/integration
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"   # Gatling JDBC example on H2
sbt coverage test coverageReport coverageOff      # coverage report (Codecov)
bash scripts/install-hooks.sh                     # enable the pre-commit git hook — once per clone
```

Default verification: `sbt scalafmtCheckAll scalafmtSbtCheck compile test`.

A pre-commit git hook (see Tooling) formats staged sources (`sbt scalafmtAll scalafmtSbt`) on every commit; compile + tests run in CI.

## Structure

<!-- A light index, not a full tree. Base Scala package: org.galaxio.gatling.jdbc -->
- `protocol/` -> JDBC protocol model, builders, Gatling wiring, shared connection settings.
- `actions/` -> query, insert, batch, raw SQL, and stored-procedure actions/builders.
- `db/` -> JDBC client, blocking execution, statements, resource handling, SQL helpers.
- `check/`, `internal/` -> check materialization, result extraction, DSL helpers.
- `src/main/java/org/galaxio/gatling/javaapi/` -> Java/Kotlin-facing facade.
- `src/test/{scala,java,kotlin}/` -> regression, integration, and usage coverage.

## Architecture

- Protocol config builds shared JDBC components, actions call the DB layer, checks map database results back into Gatling sessions.
- Treat Scala DSL, Java builders, defaults, and plugin semantics as compatibility-sensitive.
- JDBC interactions are blocking and driver-dependent: review pool sizing, resource cleanup, transaction boundaries, timeout handling, error propagation, and thread behavior carefully.
- Apply SOLID when it improves clarity and testability. Prefer KISS and DRY, but avoid premature abstraction in public APIs.

## Test Model

- Follow TDD where practical; add focused regression tests for behavior changes.
- Prefer a real database path (H2, or PostgreSQL via Testcontainers) over mocks when validating JDBC/Gatling behavior.
- `DebugTest` is the Gatling JDBC example simulation CI runs on H2.
- Preserve backward compatibility for published Scala and Java APIs.

---

<!-- ===================================================================== -->
<!-- DEVELOPMENT PROCESS — galax-io convention, adapted to this repo.       -->
<!-- ===================================================================== -->

## Boundaries

**Always:** format before commit (`sbt scalafmtAll scalafmtSbt`), branch from `main`, keep commits semantic and green, preserve backward compat for published Scala/Java APIs and downstream consumers. `build.sbt` + `project/Dependencies.scala` + `project/plugins.sbt` = dependency truth; `.github/workflows/` = CI/release truth.

**Ask first:** new deps or upgrades, changing public API signatures / observable behavior / serialized formats, editing another repo, release/publish workflow changes.

**Never:** force-push or commit to `main`, merge commits in PR branches (rebase only), commit broken code, opportunistic refactors outside scope, mock external systems where a real integration path exists, commit or publish `AGENTS.md`/`CLAUDE.md` unless the user explicitly asks.

## Milestones (ALWAYS)

Every piece of work is tied to a milestone. No exceptions unless explicitly told otherwise.

- **Every PR** is assigned to the active milestone before merging. No milestone = do not merge.
- **Every issue** fixed by a PR is closed when that PR lands on `main`. Don't leave completed issues open.
- **Spec work** (`specs/NNN-*/`) belongs to the milestone that owns the spec; link the spec PR to that milestone when you open it.
- **Active milestone** = the lowest-numbered open milestone matching the current spec/plan. Check `gh api repos/galax-io/gatling-jdbc-plugin/milestones` if unsure.

Enforced by [`scripts/check-linkage.sh`](scripts/check-linkage.sh) + the [`.claude/hooks/linkage-guard.sh`](.claude/hooks/linkage-guard.sh) PreToolUse hook (gates release tagging only; normal push/PR/merge untouched):

- `scripts/check-linkage.sh --pr <N>` — gate one PR: milestone + `Closes #<issue>` + issue in the same milestone.
- `scripts/check-linkage.sh --for-tag vX.Y.Z` — gate a release: every milestone issue closed, every PR merged.
- `scripts/check-linkage.sh` — audit the active milestone (lowest-numbered open).

> The `--for-tag` gate resolves `vX.Y.Z` to a milestone whose title starts with that **exact** version first (a dedicated patch milestone, e.g. `v1.3.1 <description>`); if none exists it falls back to the milestone whose title starts with `vX.Y.0`. Name release milestones `vX.Y.0 <description>` (or `vX.Y.Z <description>` for a dedicated patch milestone) for the gate to resolve; audit and `--pr` modes work with any milestone name.

## Commits & PRs

- **Spec-first.** `specs/NNN-*/` artifacts land as a `docs(speckit): add NNN-<feature> spec/plan/tasks` commit BEFORE any `feat`/`fix`. Never fold spec artifacts into implementation commits.
- **1 issue = 1 commit.** Each tracked GitHub issue maps to one semantic commit (`feat(scope): … (#NNN)`), green on its own (`sbt scalafmtCheckAll scalafmtSbtCheck compile test`). Docs and out-of-scope improvements go in separate PRs — never mixed with issue commits.
- **Conventional Commits drive the changelog & version.** git-cliff groups release notes by type (`feat`/`fix`/`perf`/`docs`/deps/…); the subjects since the last tag also guide the version you pick (`feat` → minor, `!:`/`BREAKING CHANGE` → major, else patch). Write accurate subjects.
- **Intent, not path.** No add-then-remove within a PR. Squash churn before review.
- **1 concern per PR.** Feature ≠ docs/README. Stack dependent PRs; update with `--force-with-lease`.
- **Idiomatic code.** Follow Scala/Java/Kotlin idioms and the conventions already in the codebase; no control-flow-by-exception, no dead or duplicated code.

## Release Process

Trunk-based with release branches. Trunk is `main`; `release/*` branches are cut from `main` for stabilization. Releases are **manual and tag-driven** — [`.github/workflows/ci.yml`](.github/workflows/ci.yml) only tests; [`.github/workflows/release.yml`](.github/workflows/release.yml) runs only on `v*` tags.

Pushing a `vX.Y.Z` tag on `main` or a `release/*` branch:

1. `release.yml` validates the tag sits on `main` or `release/*` (rejects stray tags).
2. `sbt clean compile test` runs as a release sanity gate.
3. `sbt ci-release` publishes to Sonatype (sbt-ci-release / dynver, PGP-signed).
4. Release notes are generated from Conventional Commits by git-cliff ([`cliff.toml`](cliff.toml), `orhun/git-cliff-action`), and a GitHub Release is created (`softprops/action-gh-release`).

### Minor/major release (e.g. 1.2.0, 2.0.0)

1. `git checkout -b release/X.Y.0 main`
2. `git push -u origin release/X.Y.0`
3. `git tag vX.Y.0` on the release branch
4. `git push origin vX.Y.0` — triggers `release.yml`

### Patch release (e.g. 1.2.1)

1. Fix lands on `main` first (via PR)
2. `git cherry-pick <fix-sha>` onto `release/X.Y.0`
3. `git tag vX.Y.1` on the release branch
4. `git push origin vX.Y.1`

### Milestone gate

A release milestone is **tag-ready** only when every issue in it is closed and every PR merged. Because a release is now a deliberate local `git tag vX.Y.Z` / tag push, the [`linkage-guard`](.claude/hooks/linkage-guard.sh) hook actually gates it: it runs `check-linkage.sh --for-tag vX.Y.Z` and blocks the tag until the milestone passes. Name release milestones `vX.Y.0 …`, or `vX.Y.Z …` for a dedicated patch milestone, so the gate resolves (see Milestones). To enforce the same gate server-side, add a `scripts/check-linkage.sh --for-tag "$GITHUB_REF_NAME"` step to `release.yml` once milestones are version-named.

### Rules

- **Version comes from the tag** (dynver) — pick it from the Conventional Commits since the last tag: `feat` → minor, `!:`/`BREAKING CHANGE` → major, otherwise patch.
- **Tags only on `main` or `release/*`** — `release.yml` rejects tags anywhere else.
- **Never delete a release tag** after publish starts — creates stuck registry deployments.
- **Never reuse a version number** — Sonatype rejects duplicates permanently.
- **CI in `.github/workflows/` is the source of truth** for formatting, compile, tests, coverage, and release behavior.

## Tooling

- [`scripts/check-linkage.sh`](scripts/check-linkage.sh) — issue↔PR↔milestone contract checker (see Milestones). Needs `gh` + `jq`.
- [`.claude/hooks/linkage-guard.sh`](.claude/hooks/linkage-guard.sh) — PreToolUse(Bash) hook wired in [`.claude/settings.json`](.claude/settings.json); gates release-tag pushes only, ~0 tokens otherwise.
- [`setup-speckit.sh`](setup-speckit.sh) — installs spec-kit extensions/presets. Run deliberately: needs the `specify` CLI + network and installs from third-party GitHub archives, **auto-accepting spec-kit's untrusted-source prompt** — review the pinned sources in the script before running.
- [`cliff.toml`](cliff.toml) — git-cliff config: Conventional Commits → grouped GitHub Release notes (used by `release.yml`).
- [`.githooks/pre-commit`](.githooks/pre-commit) — shared git hook (wired via `core.hooksPath`): runs `sbt scalafmtAll scalafmtSbt` and re-stages the commit's files, so every commit is scalafmt-clean (compile + tests stay in CI). `-batch` + closed stdin so a failure aborts instead of hanging. Bypass with `SKIP_SCALAFMT=1` or `git commit --no-verify`.
- [`scripts/install-hooks.sh`](scripts/install-hooks.sh) — enable it (`git config core.hooksPath .githooks`); run once per clone.
- `specs/` — spec-kit working dir; features live in `specs/NNN-<feature>/`.

## Repo Notes

- `build.sbt`, `project/Dependencies.scala`, and `project/plugins.sbt` are the source of truth for build and dependency behavior.
- Changes in `db/`, resource lifecycle, protocol wiring, or action execution can affect both correctness and observability under load.
- Real database behavior is usually more valuable than mocks here.
