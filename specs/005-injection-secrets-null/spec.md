# Feature Specification: Runtime Safety — Injection Rejection, Secret Redaction & NULL Fidelity

**Feature Branch**: `005-injection-secrets-null`

**Created**: 2026-07-22

**Status**: Draft

**Input**: User description: "https://github.com/galax-io/gatling-jdbc-plugin/milestone/8 be rationale" — specify milestone 8 (v1.5.0 — Runtime correctness: injection, secrets & NULL handling; issues #90, #91, #92, #93, #125, #126), presented rationale-first.

## Rationale

The reasoning below drives every decision in this specification. Decisions follow from the criteria, not the other way around.

### Criteria

1. **Test results must be trustworthy.** A load-testing tool that silently changes the meaning of the data it was given (for example, turning the text value `NULL` into a database NULL) produces results that measure something other than what the engineer designed. Silent alteration is worse than a loud failure, because nobody knows the results are wrong.
2. **Test artifacts are effectively public.** Simulation logs, statistics, and reports are routinely attached to CI runs, dashboards, chat threads, and tickets. Anything that reaches them — a password in a configuration dump, a customer email inside a database error message — must be treated as published. Prevention has to happen before the artifact is written, because artifacts cannot be recalled.
3. **A load generator must not be able to damage the system under test beyond its stated intent.** Load tests run against shared and staging databases at high volume. If a data value can rewrite the statement being executed (broaden an update's scope, chain an extra command), one bad feeder row can corrupt an environment thousands of times per second. The blast radius of injection in a load tool is uniquely large.
4. **Safety must be the default, not a discipline.** Users feed these tests from CSVs, generators, and production-derived datasets they do not fully control. Protection that depends on users pre-sanitizing every value will fail; unsafe paths must be rejected or redacted by default, with raw access only as an explicit, visible opt-in.

### Decisions that follow

- **Values are always data, never statement text** (criteria 3, 4): procedure names are validated as identifiers before use, and condition values in batch updates are bound as parameters. Inputs that would change the shape of a statement are rejected before anything reaches the database. → User Story 1
- **Secrets and user data are redacted before they can reach any artifact** (criteria 2, 4): configuration dumps never render passwords or secret-like properties, and database error messages are sanitized and bounded before entering statistics. Raw diagnostic detail is available only by explicit opt-in. → User Story 2
- **Every value round-trips exactly as given** (criterion 1): only an actual absent value (or the explicit null marker) means database NULL; the four-character text `NULL` is stored as text. → User Story 3
- **The `NULL`-sentinel removal is a deliberate, documented behavior change** (criterion 1 outranks strict compatibility): keeping the sentinel preserves a silent-corruption bug; the change ships in a minor release with a migration note pointing to the explicit null marker.

### Priority reasoning

Priorities order by blast radius and reversibility: injection can corrupt a shared database at load-test volume (irreversible damage to systems beyond the test) — P1. Secret and PII disclosure persists in distributed artifacts that cannot be recalled — P2. NULL mis-mapping corrupts only the data the test itself writes and is caught once known — P3.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Data values cannot change what a statement does (Priority: P1)

A performance engineer drives stored-procedure calls and batch updates from feeder data they do not fully control (CSV exports, generated datasets, production-derived samples). Whatever a value contains — quotes, semicolons, boolean expressions like `1 OR 1=1`, or a trailing `; DROP TABLE` — the operation the engineer defined is the operation that runs: hostile procedure names are rejected before execution, and condition values can only ever match rows, never widen the condition. (Issues [#90](https://github.com/galax-io/gatling-jdbc-plugin/issues/90), [#125](https://github.com/galax-io/gatling-jdbc-plugin/issues/125).)

**Why this priority**: Highest blast radius — at load-test volume a single injectable value corrupts a shared database thousands of times per second, damaging systems beyond the test itself (criterion 3).

**Independent Test**: Run procedure-call and batch-update scenarios against a real test database with hostile values in feeders; verify injected statements never execute, no rows outside the intended predicate change, and legitimate calls (including qualified procedure names and output parameters) still succeed.

**Acceptance Scenarios**:

1. **Given** a stored-procedure call whose name comes from session data, **When** the resolved name contains an injection attempt (e.g. `proc; DROP TABLE users`), **Then** the call is rejected before reaching the database, the virtual user's request is marked failed, and the injected statement never executes.
2. **Given** a stored-procedure call with a valid (optionally schema-qualified) name and output parameters, **When** it executes, **Then** it succeeds exactly as before this change.
3. **Given** a batch update whose condition compares a column to a feeder value, **When** the value is `1 OR 1=1`, **Then** only rows genuinely matching the comparison value are affected — the condition is never broadened.
4. **Given** a batch update whose condition string embeds session interpolation (an unsafe free-form predicate), **When** the scenario is constructed, **Then** the batch update is rejected before the simulation starts, with an error explaining the safe parameterized alternative.

---

### User Story 2 - Secrets and personal data never reach logs or reports (Priority: P2)

A performance engineer runs simulations with real credentials and production-like personal data (emails, passwords in feeder rows), then shares the resulting logs and reports through CI, dashboards, and chat. Nothing sensitive appears in those artifacts: connection passwords never render in configuration output, secret-like pool properties are redacted even in verbose logging, and database error messages are sanitized and bounded before they enter statistics. (Issues [#91](https://github.com/galax-io/gatling-jdbc-plugin/issues/91), [#92](https://github.com/galax-io/gatling-jdbc-plugin/issues/92), [#126](https://github.com/galax-io/gatling-jdbc-plugin/issues/126).)

**Why this priority**: Disclosure is permanent — artifacts are distributed and cannot be recalled (criterion 2) — but the damage is exposure rather than active corruption of shared systems, so it ranks below injection.

**Independent Test**: Run simulations with known marker values as password, secret-like properties, and PII-bearing feeder data; assert the markers appear nowhere in configuration dumps, verbose logs, simulation statistics, or reports, while a debug opt-in still surfaces raw detail for troubleshooting.

**Acceptance Scenarios**:

1. **Given** a protocol configured with a password, **When** its configuration is printed or logged (including in an exception), **Then** the password value is redacted.
2. **Given** a connection URL with embedded credentials, **When** it appears in any log or printed output, **Then** the credential portion is redacted.
3. **Given** custom pool properties with secret-like names (e.g. `sslpassword`, `token`), **When** verbose logging is enabled, **Then** their values are redacted; non-secret properties remain visible.
4. **Given** a database error whose message contains feeder values (an email, a password), **When** the failure is recorded in simulation statistics, **Then** the recorded message is sanitized and length-bounded and contains no feeder values.
5. **Given** the same failing scenario with the raw-message debug opt-in enabled, **When** the error occurs, **Then** the full raw database message is available to the engineer through the opt-in channel only.

---

### User Story 3 - The text "NULL" is data, not a command (Priority: P3)

A performance engineer feeds string values into inserts and updates. A row whose value is the literal four-character text `NULL` (a real surname, a tag, a CSV artifact) is stored as that text. Only an actually-absent value — or the explicit null marker the plugin provides — produces a database NULL. (Issue [#93](https://github.com/galax-io/gatling-jdbc-plugin/issues/93).)

**Why this priority**: Silent data corruption undermines result trust (criterion 1), but the damage is confined to data the test itself writes and stops recurring once fixed — narrower blast radius than P1/P2.

**Independent Test**: Insert rows carrying the text `NULL`, the text `null`, an empty string, an explicit null marker, and an absent value against a real test database; verify each is stored distinctly and correctly.

**Acceptance Scenarios**:

1. **Given** a feeder row whose string value is `NULL`, **When** it is inserted, **Then** the database column contains the four-character text `NULL`, not a database NULL.
2. **Given** a value that is genuinely absent or the explicit null marker, **When** it is inserted, **Then** the database column is NULL.
3. **Given** mixed-case variants (`null`, `Null`), **When** they are inserted, **Then** each is stored as its exact text.
4. **Given** a simulation that relied on the old `"NULL"`-string convention, **When** the user consults the release notes, **Then** a migration note explains the behavior change and the explicit null marker to use instead.

---

### Edge Cases

- Procedure name is schema-qualified (`schema.proc`), quoted, or contains legal-but-unusual identifier characters — validation must accept legitimate identifiers while rejecting statement-changing input.
- Condition value legitimately contains SQL-looking text (surname `O'Brien`, a comment string containing `--`) — it must bind as data and match literally, never alter the statement.
- Database error message is shorter than the bound, exactly at the bound, or far over it — truncation must be stable and clearly marked.
- Secret-like property names in unexpected case (`SSLPassword`) or with common secret naming (`credential`, `apikey`) — redaction matches name patterns case-insensitively over a documented pattern list. A custom property whose name matches no known secret pattern is outside the redaction guarantee; that boundary is documented (security note in the migration/README material) so users name secret-bearing properties recognizably or keep them out of verbose logging.
- Empty string vs. absent value vs. explicit null marker — all three must remain distinguishable end to end.
- A feeder value that is itself a valid procedure name but different from the intended one — validation accepts it (it is a legitimate identifier); scope protection here is identifier validation, not intent divination.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Stored-procedure names resolved from user or session data MUST be validated as (optionally schema-qualified) identifiers before any statement is assembled; input failing validation MUST cause the operation to fail without anything being sent to the database. (#90)
- **FR-002**: Valid procedure calls — including schema-qualified names and calls with output parameters — MUST continue to work unchanged. (#90)
- **FR-003**: Values used in batch-update conditions MUST be bound as data such that no value can widen, narrow, or otherwise alter the condition the user defined. (#125)
- **FR-004**: Free-form dynamic condition text that cannot be safely bound MUST be rejected by default, with an error that names the safe alternative. (#125)
- **FR-005**: Printed or logged representations of protocol configuration MUST never contain the password, including inside exception messages and connection URLs with embedded credentials. (#91)
- **FR-006**: Values of secret-like custom pool/driver properties (at minimum: password, ssl-related passwords, tokens, URL-embedded credentials) MUST be redacted from all log output at every log level. URL-credential redaction itself is defined by FR-005; this requirement applies it to the logging channel. (#92)
- **FR-007**: Database error messages recorded into simulation statistics or reports MUST be sanitized so they carry no user data values, and MUST be bounded in length; the raw message MUST remain reachable only through an explicit debug opt-in. (#126)
- **FR-008**: Only a genuinely absent value or the explicit null marker MUST map to database NULL; any string value — including the texts `NULL` and `null` — MUST be stored exactly as given. (#93)
- **FR-009**: The removal of the `"NULL"`-string convention MUST be documented as a behavior change with a migration note naming the explicit null marker.
- **FR-010**: Rejections MUST surface at the earliest point they are decidable, and never as a mid-run crash of the whole simulation: a condition detectable from author-written scenario code alone (an EL-bearing condition string, a placeholder/parameter mismatch) MUST fail fast when the scenario is constructed — before any load starts — with an error naming the safe alternative; a condition that depends on session-resolved data (an invalid resolved procedure identifier) MUST surface as a normal per-request failure of the affected virtual user, visible in results while the simulation continues.

### Key Entities

- **Feeder value**: A datum supplied by the user's dataset; may legally contain any text. Never interpreted as statement structure; never rewritten into a different value.
- **Statement identifier**: A name (procedure, optionally schema-qualified) that selects *what* to execute; validated against identifier rules, the only place user input may shape a statement.
- **Condition value**: A datum compared against a column in a batch update; bound as data, can only affect *which rows match*, never *what the condition is*.
- **Secret**: A credential or secret-like configuration value (password, token, URL credentials); must never appear un-redacted in any output.
- **Recorded error**: The sanitized, bounded message a failure leaves in statistics/reports; distinct from the raw database message, which is debug-opt-in only.
- **Null marker**: The explicit representation of "no value"; the only string-typed route to database NULL.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of attempted injection inputs in the verification suite (hostile procedure names, condition values such as `1 OR 1=1`) are rejected or bound as data; zero rows outside the intended predicate are ever modified.
- **SC-002**: Zero occurrences of configured secret values (passwords, secret-like properties, URL credentials) in any configuration dump, log line at any level, or report produced by the verification suite.
- **SC-003**: Zero occurrences of feeder data values in simulation statistics or reports for failing requests; every recorded error message respects the documented length bound.
- **SC-004**: 100% of values round-trip exactly: for every string fed (including `NULL`, `null`, empty string), the stored value equals the fed value; database NULL appears only for absent values or the explicit marker.
- **SC-005**: All previously valid simulations (procedure calls with legal names, parameterized batch updates, inserts without the `"NULL"` convention) run unchanged and produce the same outcomes as before.
- **SC-006**: A user affected by the `"NULL"` behavior change can find the migration note and the explicit null marker from the release notes alone, without reading source code.

## Assumptions

- The six issues of milestone 8 (#90, #91, #92, #93, #125, #126) bound this feature's scope; no other hardening work rides along.
- Removing the `"NULL"`-string sentinel is an intentional behavior change accepted for v1.5.0 because it fixes silent data corruption; it ships with a migration note rather than a compatibility shim (criterion 1 outranks strict compatibility).
- An explicit null marker already exists in the public API, so users of the old sentinel have a direct replacement.
- No *new* unsafe escape hatch for dynamic statement text is introduced; rejection with a clear error is the default. The pre-existing expression-based `where` overload is retained as the single deprecated, documented escape hatch (per #90's "only if required" — it already exists, so it is marked and warned rather than duplicated).
- Sanitized-by-default applies at every log level; raw database messages require a deliberate, documented debug opt-in (per #126).
- Verification runs against real databases (the project's standard test databases), not mocks, since the guarantees concern real driver and database behavior.
