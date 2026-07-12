# Quickstart: Validating Statement Concurrency & Resource-Safety Hardening

**Plan**: [plan.md](plan.md) | **Contract**: [contracts/jdbc-client-behavior.md](contracts/jdbc-client-behavior.md)

## Prerequisites

- JDK 17+, sbt (repo toolchain)
- Docker running — PostgreSQL Testcontainers specs (`BatchQueryTimeoutSpec`,
  `PostgreSQLIntegrationSpec`) skip or fail without it
- One-time per clone: `bash scripts/install-hooks.sh`

## Full gate (what CI runs)

```bash
sbt scalafmtCheckAll scalafmtSbtCheck compile test
```

Expected: all green, including the four new/extended specs.

## Per-issue validation

| Issue | Command | Expected outcome |
|-------|---------|------------------|
| #120 PS binding serialized | `sbt "testOnly org.galaxio.gatling.jdbc.db.StatementParamsConcurrencySpec"` | max concurrent setter entry == 1; H2 round-trip values 100% correct |
| #121 CS IN/OUT serialized | same spec (callable cases) + `sbt "testOnly org.galaxio.gatling.jdbc.db.PostgreSQLIntegrationSpec"` | no overlapped registration; stored-proc OUT values correct under load |
| #83 batch timeout | `sbt "testOnly org.galaxio.gatling.jdbc.db.BatchQueryTimeoutSpec"` | slow batch → `Failure` (KO) within timeout+margin; fast batch OK; no-timeout unchanged |
| #100 release on sync throw | `sbt "testOnly org.galaxio.gatling.jdbc.db.ResourceReleaseOnSyncThrowSpec"` | original exception surfaced; Hikari active connections back to 0; soak loop leak-free |

## End-to-end example simulation (regression guard)

```bash
sbt "Gatling / testOnly org.galaxio.performance.jdbc.test.DebugTest"
```

Expected: simulation passes on H2 exactly as before (Constitution II / SC-005).

## Milestone close-out check

```bash
scripts/check-linkage.sh            # audit active milestone (v1.2.0)
gh api repos/galax-io/gatling-jdbc-plugin/milestones/12 --jq '{open: .open_issues, closed: .closed_issues}'
```

Expected after implementation lands: #83, #100, #120, #121 closed via merged PR(s),
milestone open-issue count 0 → tag-ready per release discipline.
