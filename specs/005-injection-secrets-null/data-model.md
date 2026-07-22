# Data Model: Runtime Safety — Injection Rejection, Secret Redaction & NULL Fidelity

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

Models below are behavioral contracts over existing types; only `Redaction` is a new (internal) unit. Ordering mirrors the spec's entity list.

## 1. Parameter value mapping (`ParamVal` / `withParamsMap`) — #93

The single conversion table from user-supplied values to bound SQL parameters. **Invariant: total, injective on meaning — no string value may change type en route.**

| Input (runtime value) | Before (v1.4.x) | After (v1.5.0) |
|---|---|---|
| `Int`, `Long`, `Double`, `Boolean`, `LocalDateTime`, `UUID` | typed param | unchanged |
| JVM `null` | `NullParam` → SQL NULL | unchanged |
| explicit `NullParam` (via `withParams`) | SQL NULL | unchanged |
| string `"NULL"` (exact, upper) | **`NullParam` → SQL NULL (corruption)** | `StrParam("NULL")` → text `NULL` |
| strings `"null"`, `"Null"`, any other case | `StrParam` (already text) | unchanged |
| `""` (empty string) | `StrParam("")` | unchanged |
| any other object | `StrParam(v.toString)` | unchanged |

State transitions: none (pure function). Validation rule: the `"NULL"` case simply falls through to the generic `String` branch — one removed case, no new branches.

## 2. Statement identifier (`SqlIdentifier`) — #90, reused as-is

Grammar (unchanged, from #124): `segment('.' segment){0,2}`; segment = unquoted `[A-Za-z_][A-Za-z0-9_$]{0,127}` | `"…"` | `` `…` `` (quote doubled to escape); `{`, `}`, NUL forbidden anywhere; no empty/dangling segments.

New application site: `DBCallAction` procedure name, after session resolution, before `CALL` text assembly.

| Input example | Verdict |
|---|---|
| `my_proc`, `analytics.rollup`, `"weird proc"`, `` cat.`sch`.p `` | accept |
| `proc; DROP TABLE users`, `p(x)`, `a.b.c.d`, `pro{c}` | reject → KO, no SQL built |

Relationship: `ActionBase.validIdentifier` adapts `Either[InvalidSqlIdentifierException, String]` → Gatling `Validation[String]`; rejection rides the existing `crashOnFailure` KO path (FR-010: per-request failure, simulation continues).

## 3. WHERE clause states (`BatchUpdateAction.where`) — #125

A batch-update condition now has three construction states:

| State | DSL entry | Session data can enter as | Safety |
|---|---|---|---|
| **Static** | `where("status = 'ACTIVE'")` (plain string, no `#{`) | — (author-fixed text) | safe: author-owned SQL, frozen at build time |
| **Parameterized** | `where("email = {email} AND age > {min}", "email" -> "#{userEmail}", "min" -> 18)` | bound values only (`{p}` placeholders → `PreparedStatement`) | safe: values can match rows, never reshape the predicate |
| **Opaque expression** (deprecated) | `where(expr)` where `expr: Expression[String]` | resolved text (interpolated) | **unsafe escape hatch** — `@deprecated` since 1.5.0, documented |

Transition rule at DSL-construction time: plain `String` containing `#{` → **rejected** (`IllegalArgumentException` naming the parameterized overload). Post-construction there are no transitions.

Validation rules: parameterized-clause placeholder names must be disjoint from SET-value names (collision → construction-time error, deterministic message); placeholders in the clause without a matching param (or vice versa) → construction-time error. Both checks run once at build, zero hot-path cost.

Relationship: parameterized WHERE values merge into the same `SqlWithParam.params` sequence the SET values already use — one binding mechanism, one audit surface.

## 4. Secret & redaction model (`Redaction`, internal) — #91, #92, #126

New `private[jdbc]` object; the only unit allowed to turn sensitive material into printable text.

| Function | Contract |
|---|---|
| `redactUrl(url)` | `scheme://user:pass@host…` → `scheme://user:***@host…`; URLs without credentials pass through unchanged; malformed URLs returned as `<redacted url>` (never throw on the logging path) |
| `isSecretProperty(name)` | case-insensitive contains-match over `password`, `secret`, `token`, `passphrase`, `credential`, `apikey` (also matching `api-key`/`api_key` via separator-stripping). Names matching no pattern are outside the redaction guarantee — documented boundary (README security note, T018) |
| `koMessage(throwable)` | the **structured error grammar** below; total (never throws), bounded |

**Structured KO message grammar** (#126):

```text
ko-message   = primary [" [cleanup also failed: " suppressed-summary "]"]
primary      = sql-form | plugin-form | opaque-form
sql-form     = ClassName " [SQLState=" state? ", code=" int "]"     ; SQLException family
plugin-form  = ClassName ": " safe-message                          ; allowlisted plugin exceptions whose
                                                                    ; messages are data-free by construction
opaque-form  = ClassName                                            ; everything else — class name only
suppressed-summary = first-3 as sql-form/opaque-form, "; "-joined, "(+N more)" tail
```

Hard bound: total length ≤ 512 chars (constant), applied last; when the bound truncates, the message ends with `…` so truncation is visible (spec edge case: "stable and clearly marked"). Allowlist starts with the plugin's own timeout/pool-config exceptions; `InvalidSqlIdentifierException` is **not** allowlisted (its message embeds the offending value) — it reports as `opaque-form` on the KO path while the full message stays on the DEBUG channel. Raw-detail channel: the complete `Throwable` (message + suppressed + stack) logged at DEBUG on the action logger at the moment `koMessage` is built — logback level config is the opt-in switch (spec US2 scenario 5).

Config-side rules: `JdbcProtocolBuilderConnectionSettingsStep.toString` renders `password=*****` and `url=redactUrl(url)`; `JdbcProtocol.newComponents` applies `isSecretProperty` to custom data-source properties — redact-or-warn scope fixed by the R5 probe's failing assertions.

## 5. Recorded error vs. raw error — ownership map (#126)

| Channel | Content | Audience | Bound |
|---|---|---|---|
| Gatling stats / `simulation.log` / reports | structured KO message (grammar above) | anyone the report is shared with | ≤ 512 chars, value-free by construction |
| Plugin logger @ DEBUG | full raw `Throwable` | engineer who deliberately enabled DEBUG | none (explicit opt-in) |

## 6. Null marker (#93)

`NullParam` (public, existing) and JVM `null` are the only routes to SQL NULL after this feature. Distinguishability invariant (spec US3 + edge case): `"NULL"` text ≠ `""` empty string ≠ SQL NULL — all three survive a write/read round-trip distinct.
