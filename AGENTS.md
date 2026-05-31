# Agents

Local-only instructions for agents working in `gatling-jdbc-plugin`.

## Role
- Act as a Principal Engineer in software development and performance testing.
- Bring strong Scala, Java, Kotlin, Gatling plugin, and JDBC expertise.
- Prefer small, clear, backward-compatible changes unless the task explicitly requires otherwise.

## Stack
- Scala 2.13 core on SBT, Gatling 3.13.5, Java 17+.
- JDBC plugin for query, insert, batch, raw SQL, and stored procedure flows.
- Java API facade with Kotlin-compatible usage/tests.
- HikariCP, H2-backed tests, GitHub Actions, Scala Steward, Codecov, Sonatype.

## Installed Skills
- Use the installed Scala, Java, Kotlin, TDD, and unit-test skills when they apply.
- Default skill set: `scala-pro`, `java-best-practices`, `kotlin-patterns`, `kotlin-testing`, `tdd-workflow`, `unit-test-utility-methods`.
- Prefer Scala guidance for core plugin/runtime code, Java guidance for `src/main/java/.../javaapi`, Kotlin guidance for Kotlin tests/examples, and TDD plus focused regression coverage for behavior changes.

## Structure
- `protocol/`: JDBC protocol model, builders, Gatling wiring, and shared connection settings.
- `actions/`: query, insert, batch, raw SQL, and stored procedure actions/builders.
- `db/`: JDBC client, blocking execution, statements, resource handling, and SQL helpers.
- `check/` and `internal/`: check materialization, result extraction, and DSL helpers.
- `src/main/java/.../javaapi`: Java/Kotlin-facing facade.
- `src/test/scala`, `src/test/java`, `src/test/kotlin`: regression, integration, and usage coverage.

## Design Rules
- Keep architecture simple: protocol config builds shared JDBC components, actions call the DB layer, checks map database results back into Gatling sessions.
- Treat Scala DSL, Java builders, defaults, and plugin semantics as compatibility-sensitive.
- JDBC interactions are blocking and driver-dependent: review pool sizing, resource cleanup, transaction boundaries, timeout handling, error propagation, and thread behavior carefully.
- Apply SOLID when it improves clarity and testability.
- Prefer KISS and DRY, but avoid premature abstraction in public APIs.

## Working Rules
- Do not commit or publish this file unless the user explicitly asks.
- Keep changes scoped to this repo; preserve existing user changes.
- Prefer `rg` for search and `apply_patch` for edits.
- Confirm before editing another repo.
- Avoid opportunistic refactors; prefer real runtime validation over heavy mocking for JDBC/Gatling behavior.

## Quality
- **Mandatory pre-push sequence (non-negotiable):**
  ```
  sbt scalafmtAll scalafmtSbt
  sbt scalafmtCheckAll scalafmtSbtCheck
  ```
  Both commands must succeed before any `git push`. If either fails, fix formatting first.
- Default verification: `sbt scalafmtCheckAll scalafmtSbtCheck compile test`.
- Follow TDD where practical and add focused regression tests for behavior changes.
- Prefer integration tests against a real database or realistic runtime path when validating JDBC behavior.
- Preserve backward compatibility for published Scala and Java APIs.

## PR Workflow
1. Branch from `main`.
2. **Before every push:** run `sbt scalafmtAll scalafmtSbt` then verify with `sbt scalafmtCheckAll scalafmtSbtCheck`. Push is blocked until both pass. No exceptions.
3. Run the real repo checks before commit.
4. Keep commits semantic and green; no knowingly broken commits on `main`.
5. Prefer rebase-oriented history; avoid merge commits in PR branches.
6. CI in `.github/workflows` is the source of truth for formatting, compile, tests, coverage, and release behavior.
7. Releases are driven from `main` and `v*` tags; align any release/process change with the existing workflows rather than inventing a parallel path.

## Repo Notes
- `build.sbt`, `project/Dependencies.scala`, and `project/plugins.sbt` are the source of truth for build and dependency behavior.
- Changes in `db/`, resource lifecycle, protocol wiring, or action execution can affect both correctness and observability under load.
- Real database behavior is usually more valuable than mocks here.
