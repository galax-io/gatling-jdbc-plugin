# Feature Specification: Runtime Correctness — Batch Execution & ResultSet Mapping

**Feature Branch**: `003-batch-resultset-correctness`

**Created**: 2026-07-19

**Status**: Draft

**Input**: User description: "https://github.com/galax-io/gatling-jdbc-plugin/milestone/7" — milestone **v1.4.0 — Runtime correctness: batch execution & ResultSet mapping** (7 open issues: [#84](https://github.com/galax-io/gatling-jdbc-plugin/issues/84), [#86](https://github.com/galax-io/gatling-jdbc-plugin/issues/86), [#87](https://github.com/galax-io/gatling-jdbc-plugin/issues/87), [#88](https://github.com/galax-io/gatling-jdbc-plugin/issues/88), [#122](https://github.com/galax-io/gatling-jdbc-plugin/issues/122), [#123](https://github.com/galax-io/gatling-jdbc-plugin/issues/123), [#124](https://github.com/galax-io/gatling-jdbc-plugin/issues/124))

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reported success means persisted data (Priority: P1)

A performance engineer runs a simulation that inserts or updates rows and reads the OK/KO
statistics to judge whether the system under test handled the write load. When the report
says an operation succeeded, the written data is actually committed and visible in the
database. Supplying custom connection-pool settings (for example, disabling auto-commit)
must never produce a run where every write is reported OK yet nothing was persisted.
(Issue [#88](https://github.com/galax-io/gatling-jdbc-plugin/issues/88))

**Why this priority**: A load test that reports success while silently persisting nothing
corrupts every conclusion drawn from the run. This is the most damaging failure mode
because it is invisible: statistics look healthy while the test measured nothing real.

**Independent Test**: Configure a connection pool with auto-commit disabled, run a single
update that reports OK, then verify from a fresh, independent connection that the row is
present — or that the incompatible configuration was rejected up front with a clear error.

**Acceptance Scenarios**:

1. **Given** default connection settings, **When** an insert/update completes and is reported OK, **Then** the change is visible to a new, independent connection immediately after the report.
2. **Given** custom connection settings with auto-commit disabled, **When** the simulation starts or the operation runs, **Then** either the configuration is rejected with a clear, actionable error before load begins, or the operation is committed so the change is visible to a new connection.
3. **Given** the plugin manages the transaction for an operation and the operation fails, **When** the failure is reported KO, **Then** no partial change from that operation remains visible and the connection is returned to the pool in a clean state.

---

### User Story 2 - Query results keyed by the labels the author wrote (Priority: P2)

A test author writes a query using column aliases (`SELECT id AS customer_id …`) and then
references those aliases in checks and saved session values. The result keys are exactly
the labels written in the query — not the physical column names — and a query that yields
the same label twice (for example, a JOIN with two `id` columns) never silently drops one
of the values. (Issues [#122](https://github.com/galax-io/gatling-jdbc-plugin/issues/122), [#123](https://github.com/galax-io/gatling-jdbc-plugin/issues/123))

**Why this priority**: Checks and saved values are the core reason to use this plugin over
plain load generation. Keys that ignore aliases make documented usage fail, and silent
value loss on duplicate labels makes check results untrustworthy without any warning.

**Independent Test**: Run an aliased query on two database engines with differing
identifier-case conventions and assert the result keys equal the aliases; run a JOIN
producing a duplicate label and assert the behavior is explicit and deterministic.

**Acceptance Scenarios**:

1. **Given** a query with a column alias, **When** a check or saved value references the alias, **Then** the value is found under that alias on every supported database engine.
2. **Given** a query without aliases, **When** results are extracted, **Then** keys follow a documented, deterministic case rule for each supported engine.
3. **Given** a query whose result contains the same label twice, **When** the operation executes, **Then** it fails with an explicit error naming the duplicated label(s) — no value is ever silently overwritten or lost.

---

### User Story 3 - Failure reports carry the root cause (Priority: P3)

An engineer triaging a failed batch sees the original database error (for example, a
constraint violation) in the simulation report and logs, even when the cleanup that
follows the failure (statement close, rollback) also fails. Cleanup problems are attached
as secondary information, never substituted for the real cause.
(Issue [#84](https://github.com/galax-io/gatling-jdbc-plugin/issues/84))

**Why this priority**: Masked root causes turn a five-minute diagnosis into hours of
guesswork. The information exists at failure time; the report must not discard it.

**Independent Test**: Force a batch execution failure while also forcing the subsequent
cleanup to fail, then assert the reported failure is the original execution error with the
cleanup error attached as secondary detail.

**Acceptance Scenarios**:

1. **Given** a batch that fails during execution, **When** closing the statement also fails, **Then** the reported failure is the original execution error and the close failure is attached as suppressed/secondary information.
2. **Given** a batch that fails during execution, **When** the rollback that follows also fails, **Then** the reported failure is still the original execution error with the rollback failure attached as secondary information.

---

### User Story 4 - Load-only queries do not exhaust the load generator (Priority: P4)

An engineer uses a large SELECT purely to generate read load on the database, with no
checks that consume rows. The load generator does not retain the full result in memory,
so a query returning millions of rows cannot crash the test run. When rows are needed for
checks, the engineer can cap how many rows are retained and knows what happens when the
cap is exceeded. (Issue [#86](https://github.com/galax-io/gatling-jdbc-plugin/issues/86))

**Why this priority**: An out-of-memory crash kills the entire run and every scenario in
it, but it only occurs with large results — narrower blast radius than silently wrong
results, wider than a single failed request.

**Independent Test**: Run a query returning a very large result with no row-consuming
checks and observe stable memory; run the same query with a configured row cap and verify
the documented over-cap behavior.

**Acceptance Scenarios**:

1. **Given** a query with no checks that consume rows, **When** it executes over a very large result, **Then** rows are not retained, memory stays bounded, and response time is still measured and reported.
2. **Given** a configured row cap of N, **When** the result exceeds N, **Then** the documented over-cap behavior occurs (explicit failure or documented truncation) and memory used for rows never exceeds the cap.
3. **Given** a result within the cap and checks present, **When** the operation completes, **Then** checks see every returned row exactly as before.

---

### User Story 5 - Large-object values remain usable in checks (Priority: P5)

A test author checks columns holding large binary or text values (BLOB/CLOB). The
extracted values can be read — content and length — after the query completes, instead of
failing because the value was only a live handle into a connection that has already been
returned. (Issue [#87](https://github.com/galax-io/gatling-jdbc-plugin/issues/87))

**Why this priority**: Affects correctness for a specific column-type family; failures are
loud (an error, not silent wrong data), so it ranks below the silent-loss and whole-run
stability stories.

**Independent Test**: Insert a row with binary and text large objects, query it, and
assert a check can read the full content after the operation has completed.

**Acceptance Scenarios**:

1. **Given** a query returning a large binary value, **When** a check reads its content or size after execution completes, **Then** the bytes are fully available with no invalid-handle error.
2. **Given** a query returning a large text value, **When** a check reads it, **Then** the full text is available as an ordinary string value.
3. **Given** a query returning array or XML-typed columns, **When** results are extracted, **Then** the behavior is documented and deterministic — either a stable, usable representation or an explicit, clearly worded unsupported-type failure.

---

### User Story 6 - Dynamic table and column names are validated (Priority: P6)

An engineer drives table or column names from feeder data (for example, sharded table
names). A malformed or malicious value cannot change the structure of the statement or
smuggle in additional statements: identifiers are validated against a strict, documented
grammar before any statement is built, and invalid ones fail that request with a clear
error. (Issue [#124](https://github.com/galax-io/gatling-jdbc-plugin/issues/124))

**Why this priority**: Guards the system under test from corrupted or unintended
statements. Ranked last only because feeder data is normally authored by the same
engineer running the test; the failure requires bad input rather than occurring in every
run.

**Independent Test**: Feed identifier values containing statement-structure characters
into a dynamic-table operation and assert each request fails validation with nothing sent
to the database, while legitimate identifiers keep working.

**Acceptance Scenarios**:

1. **Given** a feeder value containing statement-altering content (quotes, semicolons, comment markers), **When** the operation resolves it as a table or column name, **Then** the request fails with a validation error and no statement is sent to the database.
2. **Given** a valid plain or schema-qualified identifier, **When** the operation runs, **Then** it executes exactly as it does today.
3. **Given** an identifier that requires quoting (reserved word, unusual characters), **When** the documented quoting policy is followed, **Then** the operation executes; the policy is documented and covered by tests.

### Edge Cases

- A query aliases one column to the physical name of another column in the same result — label uniqueness must be judged on the final labels, not the source columns.
- Duplicate labels where the duplicate value is never referenced by any check — behavior must still be deterministic and explicit, not silently tolerated-and-lossy.
- Supported database engines report label case differently (one upper-cases, one lower-cases unquoted names) — the documented case rule must hold on both.
- Auto-commit disabled combined with the batch path, which already manages its own transaction — both write paths must give the same persistence guarantee.
- Cleanup fails because the connection itself has died (rollback throws on a broken connection) — the original execution error must still be the reported cause.
- A row cap set exactly equal to the result size, and a result of zero rows — both must behave without error and without over-cap handling triggering.
- Null or empty large-object values — must extract as absent/empty, not as an error.
- Schema-qualified identifiers, identifiers at the engine's length limit, and reserved words — accepted only via the documented grammar and quoting policy.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Any data-modification operation reported as successful MUST be durably persisted and visible to a new, independent connection at the moment success is reported. User-supplied connection or pool configuration MUST NOT silently defeat this guarantee: configurations incompatible with it are either rejected with a clear error before load starts, or handled so the guarantee still holds. ([#88](https://github.com/galax-io/gatling-jdbc-plugin/issues/88))
- **FR-002**: When the plugin manages transaction state and an operation fails, the operation's work MUST be rolled back and the connection returned to the pool in a clean state. ([#88](https://github.com/galax-io/gatling-jdbc-plugin/issues/88))
- **FR-003**: When statement execution fails and a subsequent cleanup step (close, rollback) also fails, the reported failure MUST be the original execution error, with cleanup errors attached as secondary/suppressed information. A cleanup error MUST never replace the primary error. ([#84](https://github.com/galax-io/gatling-jdbc-plugin/issues/84))
- **FR-004**: Query result rows MUST be keyed by the column labels as expressed in the query, honoring aliases. Label case handling MUST be deterministic and documented, and verified on at least two supported database engines with differing identifier-case conventions. ([#122](https://github.com/galax-io/gatling-jdbc-plugin/issues/122))
- **FR-005**: A result containing the same label more than once MUST never silently discard a value: the operation fails with an explicit error naming the duplicated label(s). ([#123](https://github.com/galax-io/gatling-jdbc-plugin/issues/123))
- **FR-006**: Large binary and text values MUST be fully consumable by checks after the operation completes, independent of any live database resource. Array- and XML-typed columns MUST have a documented, deterministic behavior — a stable usable representation or an explicit unsupported-type failure. ([#87](https://github.com/galax-io/gatling-jdbc-plugin/issues/87))
- **FR-007**: Query execution MUST support bounded row retrieval: (a) when no configured check consumes rows, rows are not retained and memory stays bounded regardless of result size; (b) a per-operation row cap is available, with documented behavior when a result exceeds it; (c) without an explicit cap, existing observable behavior for check-consuming queries is preserved. ([#86](https://github.com/galax-io/gatling-jdbc-plugin/issues/86))
- **FR-008**: Dynamically supplied table and column identifiers MUST be validated against a strict, documented grammar before any statement is constructed. Invalid identifiers MUST fail that request with a clear reason and send nothing to the database. The quoting policy for identifiers needing it MUST be documented and tested. ([#124](https://github.com/galax-io/gatling-jdbc-plugin/issues/124))
- **FR-009**: Every behavior above MUST be covered by automated regression tests running against a real database, and all existing published interfaces, defaults, and the example simulation MUST keep working unchanged apart from the documented defect fixes themselves.

### Key Entities

- **Operation Outcome**: The OK/KO verdict and timing recorded for one database operation; OK now carries a persistence guarantee, KO carries the primary cause plus secondary cleanup detail.
- **Result Row**: One row of a query result as seen by checks — an ordered mapping from column label (alias-aware, deterministic case) to a self-contained value.
- **Column Label**: The author-visible key for a value in a result row; must be unique within a row or the operation fails explicitly.
- **Large-Object Value**: Binary or text content extracted from a result in a form readable after the operation completes.
- **Row Cap**: An optional per-operation bound on retained rows, with defined over-cap behavior.
- **Dynamic Identifier**: A table or column name resolved at runtime from session/feeder data; subject to grammar validation and a documented quoting policy.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of operations reported successful are observable from a new, independent connection immediately after the report, under every accepted connection configuration; incompatible configurations are rejected before any load runs.
- **SC-002**: 100% of execution failures with failing cleanup still report the original database error as the primary cause, with the cleanup error preserved as secondary detail.
- **SC-003**: Checks referencing column aliases resolve correctly on at least two supported database engines with differing case conventions, without the test author ever needing physical column names for aliased columns.
- **SC-004**: Zero silent value loss across all duplicate-label scenarios — every such query produces an explicit, named-label failure.
- **SC-005**: A load-only query returning at least 1,000,000 rows completes with load-generator memory growth independent of row count, and response timing is still reported.
- **SC-006**: 100% of malformed dynamic identifiers are rejected before any statement reaches the database; zero statements with altered structure are ever sent.
- **SC-007**: The full existing automated test suite and the example simulation pass unchanged — zero regressions attributable to this milestone.

## Assumptions

- Scope is exactly the seven open issues in milestone [v1.4.0](https://github.com/galax-io/gatling-jdbc-plugin/milestone/7); no other audit findings are included.
- The audit evidence (baseline commit `a8d0401`) still reflects current `main`; if an issue turns out to be already fixed, it is closed with a regression test rather than re-implemented.
- Keying results by alias where the physical column name was previously exposed is a defect fix, documented in release notes — not a breaking change requiring a major version (target release v1.4.0, minor).
- Duplicate labels default to explicit failure (strictness over silent loss); a duplicate-preserving representation is out of scope unless requested later. The documented workaround is aliasing columns uniquely.
- The default row-retrieval behavior for queries with checks remains unlimited (today's behavior) to preserve compatibility; the no-check discard path applies automatically because it has no observable effect on results.
- Large-object values change from unusable live handles to materialized, readable values — a defect fix, since the previous behavior always failed once the operation had completed.
- The identifier grammar covers plain and schema-qualified names plus one documented quoting form; exotic identifiers must use the documented quoting.
- All changes preserve published Scala and Java API signatures and defaults (constitution Principle I); verification uses real database engines (Principle II).
