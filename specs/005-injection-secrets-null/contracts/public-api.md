# Public API Contract: Runtime Safety (v1.5.0)

**Feature**: [../spec.md](../spec.md) | **Data model**: [../data-model.md](../data-model.md)

Compatibility stance (constitution I): every existing signature keeps compiling and linking. Deltas are additive overloads, one deprecation, and two documented runtime-behavior changes. Anything not listed here is contractually unchanged.

## Scala DSL (`org.galaxio.gatling.jdbc.actions.actions`)

### `BatchUpdateValuesStepAction` — #125

```scala
// ADDED — static clause; rejects Gatling-EL at construction time
def where(whereClause: String): BatchUpdateAction
// throws IllegalArgumentException if whereClause contains "#{"
// message names the parameterized overload as the replacement

// ADDED — parameterized clause; values bound as data via {name} placeholders
def where(whereClause: String, params: (String, Expression[Any])*): BatchUpdateAction
// construction-time errors (IllegalArgumentException, deterministic messages):
//   - whereClause contains "#{"
//   - placeholder in clause with no matching param, or param with no placeholder
//   - param name colliding with a SET-value name

// DEPRECATED (kept, unchanged behavior) — the explicit unsafe escape hatch
@deprecated("Unsafe: resolved text is interpolated into SQL. Use where(String) or where(String, params*).", "1.5.0")
def where(whereExpression: Expression[String]): BatchUpdateAction
```

Overload-resolution note (source compat): `where("literal")` previously lifted to the `Expression` overload; it now selects `where(String)` — same semantics for EL-free literals, rejection for EL-bearing ones (**behavior change, deliberate**, see Migration). Compiled v1.4.x callers still link against the `Expression` overload.

### `DBCallActionParamsStep` / `DBCallActionBuilder` — #90

No signature change. **Runtime contract added**: the resolved procedure name must satisfy the `SqlIdentifier` grammar (≤3 dot-segments, unquoted `[A-Za-z_][A-Za-z0-9_$]{0,127}` or ANSI/backtick-quoted; `{`,`}`, NUL never). Violation → per-request KO before any SQL is assembled; simulation continues. Valid schema-qualified names and OUT parameters behave exactly as before.

## Java facade (`org.galaxio.gatling.javaapi.actions`)

### `BatchUpdateValuesStepAction` — #125

```java
// CHANGED (same signature, added rejection): throws IllegalArgumentException
// when whereExpression contains "#{"; EL-free strings behave as before
public BatchUpdateAction where(String whereExpression)

// ADDED — parameterized variant; placeholder/param validation as in Scala
public BatchUpdateAction where(String whereClause, Map<String, Object> params)
```

## Protocol (`org.galaxio.gatling.jdbc.protocol`)

### `JdbcProtocolBuilderConnectionSettingsStep` — #91

No signature change; `apply`/`copy`/`unapply` untouched. **String-form contract added**: `toString` never contains the password value; URLs render with credentials as `user:***@`. This string form is diagnostic output, not parseable API.

### `JdbcProtocol` components — #92

No signature change. **Logging contract added**: plugin-emitted log lines never contain values of secret-like custom data-source properties (name matching, case-insensitive: `password`, `secret`, `token`, `passphrase`, `credential`, `apikey` incl. `api-key`/`api_key`) nor URL credentials; names matching no pattern are outside the guarantee (documented README security note); a custom property with a secret-like name that the pool would print triggers redaction-or-warning at protocol build time. Exact enforcement points fixed by the R5 probe test.

## Error reporting (all actions) — #126

**Message-format contract** (applies to KO messages written to Gatling stats / `simulation.log` / reports):

```text
SQLException family:   <ClassName> [SQLState=<state>, code=<int>]
allowlisted plugin ex: <ClassName>: <message>          (messages data-free by construction)
anything else:         <ClassName>
cleanup suffix (#84):  … [cleanup also failed: <entries>; (+N more)]
total length:          ≤ 512 chars; a truncated message ends with "…"
```

Guarantee: no feeder/session/database *values* can appear in these messages — they are rebuilt from structured fields, not filtered. Full raw exception (message, suppressed, stack) is logged at DEBUG on the action logger; enabling that logger is the documented opt-in for raw detail. **The KO message text itself is diagnostic output, not parseable API** — but the value-free and bounded guarantees are contractual from v1.5.0.

## Value mapping (`org.galaxio.gatling.jdbc.db`) — #93

`SQL.withParamsMap` contract change (**deliberate behavior change**): the string `"NULL"` is bound as the 4-char text, like any other string. SQL NULL is produced only by JVM `null` or explicit `NullParam`. All other type mappings unchanged.

## Migration notes (release notes / README)

1. **`"NULL"` string sentinel removed (#93)** — if a feeder/scenario used the value `"NULL"` to mean SQL NULL: pass JVM `null` (Java/Kotlin maps allow null values) or `NullParam` (Scala `withParams`). Data previously silently NULLed will now be stored as text — this is the fix, not a regression.
2. **EL inside `where(...)` strings rejected (#125)** — `where("email = '#{email}'")` now fails at scenario-build time. Replace with `where("email = {email}", "email" -> "#{email}")` (Scala) / `where("email = {email}", Map.of("email", "#{email}"))` (Java). Author-fixed clauses without EL are unaffected. The `Expression[String]` overload remains as a deprecated unsafe escape hatch.
3. **KO messages in reports are now structured** (`ClassName [SQLState=…, code=…]`) — full driver prose moved to the plugin's DEBUG log. Anything parsing report error strings (never contractual) must adapt.
