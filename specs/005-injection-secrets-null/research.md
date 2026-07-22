# Phase 0 Research: Runtime Safety — Injection Rejection, Secret Redaction & NULL Fidelity

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-07-22

Each decision states its reasoning first, then the choice, then what was rejected. All code references verified against branch HEAD (`2284c2c` lineage), not the audit baseline `a8d0401` — every issue was re-confirmed live before planning (none were fixed in passing by the #124/#84/#87 work).

## R1 — Procedure-name validation (#90): reuse `SqlIdentifier`, do not invent a second grammar

**Reasoning**: The injection vector is `DBCallAction.makeCallString` interpolating the session-resolved `procedureName` into `CALL $name (...)` ([DBCallAction.scala:26-31](../../src/main/scala/org/galaxio/gatling/jdbc/actions/DBCallAction.scala)). The repo already owns an allowlist identifier grammar — `SqlIdentifier` (#124): unquoted `[A-Za-z_][A-Za-z0-9_$]{0,127}`, ANSI/backtick quoting with doubling escapes, up to 3 dot-joined segments, `{`/`}`/NUL forbidden everywhere. That grammar already covers the issue's acceptance needs: schema-qualified names (`schema.proc` = 2 segments) pass, `proc; DROP TABLE users` fails (`;` and space are not identifier characters). A second, call-specific grammar would drift from the insert/batch one and double the audit surface.

**Decision**: In `DBCallAction.execute`, resolve then validate: `pName <- procedureName(session).flatMap(validIdentifier)` (the `ActionBase.validIdentifier` bridge already adapts `Either` → Gatling `Validation`). Failure flows to the existing `crashOnFailure` KO path — request fails, simulation continues, nothing reaches the database (spec FR-001, FR-010).

**Alternatives considered**: (a) `DatabaseMetaData.getProcedures` existence check — a network round-trip per request on the hot path, and existence ≠ safety; rejected. (b) Escaping/quoting the name instead of validating — quoting rules are driver-specific and `{`/`}` would still break the placeholder interpolator; rejected. (c) New relaxed grammar permitting arbitrary characters inside quotes — reopens the placeholder-corruption hole #124 closed; rejected.

## R2 — Parameterized WHERE (#125): additive overloads, build-time rejection of provable EL, deprecated escape hatch

**Reasoning**: The vector is `DBBatchAction`'s second branch interpolating the *resolved* `whereExpression` into the UPDATE text ([DBBatchAction.scala:41-51](../../src/main/scala/org/galaxio/gatling/jdbc/actions/DBBatchAction.scala)) — marked "deliberately not validated" as author-owned SQL. The author-owned-SQL contract is sound *only while no session data enters the text*; Gatling EL (`#{value}`) is exactly the mechanism that injects feeder data into it. Post-compilation, an `Expression[String]` is an opaque function — inspection is impossible. But at the DSL boundary the raw `String` is still visible (Scala implicit conversion happens at the call site; the Java facade receives a plain `String` and compiles it itself in `BatchUpdateValuesStepAction.where`). So unsafety is decidable exactly at construction time, exactly for plain strings: presence of `#{` marks session interpolation. Three-part shape follows: safe static strings keep working, provably unsafe strings fail fast with a message naming the replacement, dynamic values get a first-class parameterized route through the machinery batch updates already use for SET values (`{param}` placeholders → `SqlWithParam` → `PreparedStatement` binding).

**Decision**:
- New Scala overloads on `BatchUpdateValuesStepAction`: `where(whereClause: String)` (static; throws `IllegalArgumentException` at DSL-construction time if it contains `#{`, message names the parameterized overload) and `where(whereClause: String, params: (String, Expression[Any])*)` (static clause with `{param}` placeholders; values bound as data, merged into the batch's `SqlWithParam` params alongside SET values).
- Existing `where(whereExpression: Expression[String])` stays for source/binary compatibility, annotated `@deprecated(..., "1.5.0")` and documented as the explicit unsafe escape hatch (satisfies #90's "escape hatch only if required" without adding new unsafe surface).
- Java facade: `where(String)` gains the same `#{` rejection; new `where(String, Map<String, Object>)` mirrors the parameterized overload. Existing compiled callers keep linking.
- Overload resolution note: `where("literal")` previously selected the `Expression` overload via implicit lifting; with a direct `String` overload present, Scala prefers the exact type — static literals silently migrate onto the safe path. Same-shape H2 test proves `1 OR 1=1` as a bound value matches zero extra rows.

**Alternatives considered**: (a) Runtime detection of injection in the resolved string — undecidable; the resolved text carries no marker of which characters came from data; rejected. (b) Hard-removing `where(Expression)` — binary break for compiled consumers, constitution I violation in a minor release; rejected. (c) Warning instead of rejecting EL strings — the vulnerable path keeps working silently, violating spec criterion 4 (safe by default); rejected. (d) A full WHERE-AST/builder DSL (`col("x").eq(param)`) — large new public API for what placeholders already express; premature abstraction (constitution V); rejected.

## R3 — Builder `toString` redaction (#91): override on the case class, keep the shape

**Reasoning**: `JdbcProtocolBuilderConnectionSettingsStep` is a `final case class` with `password: String` ([JdbcProtocolBuilder.scala:28-37](../../src/main/scala/org/galaxio/gatling/jdbc/protocol/JdbcProtocolBuilder.scala)) — the synthesized `toString` prints the password whenever the object is logged, printed, or embedded in an exception (the issue's failure scenario). The URL field can also embed credentials (`jdbc:postgresql://user:pass@host/db`). Removing the case class or making fields private breaks `apply`/`copy`/`unapply` users; an overridden `toString` changes only the string form, which is not a semantic contract.

**Decision**: `override def toString` on `JdbcProtocolBuilderConnectionSettingsStep` rendering `password=*****` and the URL through a credential-redacting helper (`user:***@`). Helper lives in new `private[jdbc]` `Redaction` object (shared with R5/R6). Test asserts marker values never appear in `toString` output; `copy`/`apply` behavior untouched.

**Alternatives considered**: (a) Wrapping password in an opaque `Secret` type — public signature change (`String` → `Secret`), source break; rejected for 1.5.0 (viable in a future major). (b) Regular class instead of case class — removes synthesized members consumers may use; rejected.

## R4 — Sanitized error reporting (#126): structured value-free message, raw text behind DEBUG

**Reasoning**: `ActionBase.reportError` sends `messageWithSuppressed(exception)` into `logResponse` — the *primary* `exception.getMessage` flows raw and unbounded (the #84 bounding covers only the suppressed-cleanup summary, which itself embeds `s.getMessage.take(200)` — also raw driver text). Driver constraint errors echo row values (H2 prints the offending VALUES tuple; PostgreSQL's `DETAIL: Key (email)=(…) already exists`), so feeder PII lands in `simulation.log` and reports. Filtering free text (regex for emails etc.) can never *prove* absence of user data; rebuilding the message from fields that structurally cannot contain data values can. JDBC already provides exactly those fields: exception class, `SQLState`, vendor `errorCode`. The full raw message remains one logger call away — logging it at DEBUG on the plugin's logger makes the existing logback config the opt-in switch (no new API, matching the issue's "raw values opt-in DEBUG only").

**Decision**: Replace the KO message construction in `ActionBase` with a structured builder in `Redaction`: `SQLException` family → `ClassName [SQLState=…, code=…]`; plugin-authored exceptions with data-free messages by construction (`InvalidSqlIdentifierException`, `DuplicateColumnLabelException`, timeout wrapper) → keep their message; any other throwable → class name only. Suppressed-cleanup summary (#84) rebuilt under the same rule (class + SQLState, no free text). Total message hard-bounded (constant, ~500 chars). At the same point, log the full raw exception at DEBUG via the action logger — the opt-in channel. Spec scenarios 4–5 map 1:1.

**Alternatives considered**: (a) Regex-scrubbing the driver message (mask emails, quoted literals) — blacklist approach, unprovable, driver-format-dependent; rejected. (b) Keeping raw messages and documenting the risk — violates criterion 2 (artifacts are public); rejected. (c) New DSL flag `.rawErrors(true)` — new public API for a diagnostic concern logback already handles; rejected (constitution V). Trade-off accepted: KO messages in reports become less prose-readable (class + SQLState instead of driver prose); triage detail moves to DEBUG log. `InvalidSqlIdentifierException.getMessage` embeds the offending value by design — under this rule it must either drop the value from `getMessage` or be reported class-name-only; resolved in data-model (the KO path prints it value-free, the DEBUG path keeps the value).

## R5 — Hikari secret properties (#92): probe test first, fix only proven gaps

**Reasoning**: The claim is that custom data-source properties (`sslpassword`, `token`, …) reach DEBUG logs unmasked through `new HikariDataSource(protocol.hikariConfig)`. HikariCP has its own masking in config logging (property names containing `password` are historically masked; behavior varies by version), and this project pins HikariCP 7.1.0. Guessing the mask coverage from memory risks building redaction for holes that do not exist while missing ones that do (e.g. URL-embedded credentials, `token`). The issue's own acceptance test — DEBUG-capture over `password`, `sslpassword`, `token`, URL credentials — *is* the discovery instrument, and re-running it on every Hikari upgrade keeps the guarantee alive (constitution II: real path, no mocks).

**Decision**: Write the logback-capture probe first: start a real `HikariDataSource` on H2 at DEBUG with marker values in all four positions; assert no marker appears in any captured line. Where Hikari already masks — the test simply passes and pins the behavior. For proven gaps, fix at `JdbcProtocol.newComponents` (the single choke point): redact-or-warn on secret-like property names (case-insensitive match on `password`/`secret`/`token`/`passphrase`) before the config reaches the pool/log, and route URL credentials through the R3 redaction helper in anything the plugin itself logs. Scope of code change = exactly the failing assertions, no more.

**Alternatives considered**: (a) Implementing a blanket redaction wrapper around HikariConfig unconditionally — duplicates Hikari's own masking, breaks on Hikari internals changes, adds untested surface; rejected. (b) Forbidding custom secret-like properties outright — legitimate drivers need `sslpassword`-class settings; rejected in favor of redact/warn. (c) Trusting Hikari release notes instead of a local probe — not evidence, and silently invalidated by upgrades (Scala Steward bumps); rejected.

## R6 — "NULL" sentinel removal (#93): delete the mapping, migrate via note

**Reasoning**: [db/package.scala:29](../../src/main/scala/org/galaxio/gatling/jdbc/db/package.scala) maps the string `"NULL"` to `NullParam` inside `withParamsMap`, so a legitimate feeder value `"NULL"` silently becomes database NULL — spec criterion 1's canonical case. The explicit routes already exist and stay: JVM `null` → `NullParam` (line 34) and public `NullParam` itself. With replacements in place, the sentinel is pure hazard; any preserving option (flag, warning) preserves the corruption by default.

**Decision**: Delete the `case (k, "NULL") => (k, NullParam)` line. H2 regression proves `"NULL"`, `"null"`, `"Null"` are stored as their exact text, JVM `null` and explicit `NullParam` produce SQL NULL, and empty string stays empty string (spec US3 + edge cases). Migration note names `NullParam`/JVM `null` as the replacement — satisfies FR-009/SC-006. Delivery detail: git-cliff renders commit **subjects** only (body text is used just for BREAKING-CHANGE grouping), so the release-notes channel is the docs commit whose *subject* names the change and replacement (T018); the README section carries the full before/after detail. Flagged as deliberate behavior deviation in plan Complexity Tracking.

**Alternatives considered**: (a) Deprecation cycle with runtime warning on `"NULL"` values — warns thousands of times at load rate and still corrupts the write; rejected. (b) Config flag to re-enable — permanent API surface for a bug; rejected. (c) Case-sensitive narrowing (only exact `"NULL"`) — still corrupts exactly the documented case; rejected.

## R7 — Log-assertion technique (shared by #91/#92/#126 tests)

**Reasoning**: Three fixes claim "never appears in logs" — negative assertions over logging output. The test classpath already carries logback via Gatling test deps; an attached in-memory appender on the relevant loggers (plugin loggers, `com.zaxxer.hikari.*`) captures actual output with levels forced to DEBUG for the test — the strongest honest claim: "not present even at the most verbose level". Marker values (e.g. `s3cr3t-MARKER-91`) make absence checks grep-exact and immune to formatting drift.

**Decision**: One small test helper (test scope only) that installs/removes a capturing appender around a block and returns captured lines; used by the R3/R4/R5 suites. Not published API.

**Alternatives considered**: mocking loggers (violates constitution II's spirit — the real appender path is available); parsing `simulation.log` from a full Gatling run for unit-level assertions (slow, format-coupled; reserved for the existing `DebugTest`-style end-to-end check only where stats-level proof is needed for #126).

## Resolved unknowns summary

| Unknown | Resolution |
|---------|------------|
| Does `SqlIdentifier` cover qualified/quoted procedure names? | Yes — 3 dot-segments, ANSI/backtick quoting (read from source; R1) |
| Can EL-unsafety be detected? | Only pre-compilation, only for plain strings — hence build-time rejection at the DSL boundary (R2) |
| Exact Hikari 7.1.0 masking coverage | Deliberately resolved by probe test during implementation (R5) — evidence over recall, survives upgrades |
| How to guarantee "no user data" in KO messages | Structural rebuild from class/SQLState/code — provable, unlike filtering (R4) |
| Migration path for `"NULL"` users | `NullParam` / JVM `null`, already public (R6) |
