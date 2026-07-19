# Quickstart Validation: Post-Review Follow-Ups (004)

Runnable checks proving the four items land correctly. Prerequisites: JDK 17+, sbt; repo root of the `004-review-followups` branch. Contracts referenced: [behavior-and-docs-contract.md](contracts/behavior-and-docs-contract.md).

## 1. Default green gate (FR-005 — always first and last)

```bash
sbt scalafmtCheckAll scalafmtSbtCheck compile test
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"
```

Expected: both green, zero test modifications outside the one strengthened test (verify with `git diff --stat main -- src/test` → only `QueryActionBuilderCheckChainSpec.scala`).

## 2. Consolidated KO path (FR-003 / C1)

```bash
# exactly one KO-reporting call site for check failures in DBQueryAction:
grep -c '"Check ERROR"' src/main/scala/org/galaxio/gatling/jdbc/actions/DBQueryAction.scala   # expect: 1
# both failure modes still covered by real-database suites:
sbt "testOnly org.galaxio.gatling.jdbc.actions.ThrowingCheckSpec org.galaxio.gatling.jdbc.actions.SessionMarkAsFailedSpec org.galaxio.gatling.jdbc.actions.ActionSessionFailureSpec org.galaxio.gatling.jdbc.actions.QueryActionBuilderCheckChainSpec"
```

Expected: grep count 1; all suites green with their assertions unmodified.

## 3. Strengthened regression probe (FR-004 / SC-004 mutation check)

Temporarily reintroduce the #79 regression, expect the third check-chain test to fail on its own, then restore:

```bash
# mutate: append → replace (the pre-1.3.0 bug) in the Scala builder
perl -pi -e 's/copy\(checks = checks \+\+ newChecks\)/copy(checks = newChecks)/' src/main/scala/org/galaxio/gatling/jdbc/actions/actions.scala
sbt "testOnly org.galaxio.gatling.jdbc.actions.QueryActionBuilderCheckChainSpec"   # expect: FAILS, including the third test
git checkout -- src/main/scala/org/galaxio/gatling/jdbc/actions/actions.scala
sbt "testOnly org.galaxio.gatling.jdbc.actions.QueryActionBuilderCheckChainSpec"   # expect: green
```

(If the local `git` wrapper mangles output, use `/usr/bin/git`.)

## 4. Documentation content (FR-001, FR-002 / C2, C3)

```bash
# README: Java upgrade note present in Checks section, batch rules present in Batch Operations
grep -n "returns a new builder" README.md
grep -n "adjacent" README.md
# Release notes: Upgrade notes appended, changelog sections untouched
gh release view v1.3.0 --repo galax-io/gatling-jdbc-plugin --json body --jq .body | grep -A2 "Upgrade notes"
```

Expected: README hits inside `## Checks`→`### Java` and `### Batch Operations`; release body contains `### Upgrade notes` after the existing sections, with the original sections byte-identical. Verify each content element against C2/C3 checklists.

## 5. Traceability (Constitution IV)

```bash
gh issue list --repo galax-io/gatling-jdbc-plugin --milestone v1.3.1
git log --oneline main..HEAD
```

Expected: four issues (one per item) in milestone v1.3.1; commit sequence = 1 spec-docs commit + 4 semantic commits (`refactor(actions)`, `docs(readme)` ×2, `test(actions)`), each referencing its issue.
