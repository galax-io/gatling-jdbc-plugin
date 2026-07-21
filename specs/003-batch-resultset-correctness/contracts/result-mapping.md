# Contract: ResultSet → Session Mapping

**Feature**: `003-batch-resultset-correctness` | Implements FR-004/005/006/007 (#122, #123, #87, #86), decisions R1–R4 (revised 2026-07-21)

The mapping contract for what checks and `saveAs` see after a query. Check input type is
unchanged: `List[Map[String, Any]]`.

## Keys

1. Key = `ResultSetMetaData.getColumnLabel(i)`, verbatim — alias when the query says
   `AS`, column name otherwise.
2. No case normalization. Unquoted identifiers surface in the engine's reported case:
   H2 → `CUSTOMER_ID`, PostgreSQL → `customer_id`. Quoted aliases surface exactly as
   written. This is asserted per engine in tests and documented for users (README task
   T026e).
3. Labels are read **once per ResultSet** (metadata hoisted out of the row loop).
4. Duplicate labels (after aliasing) abort the operation with
   `DuplicateColumnLabelException` naming every duplicate, before any row is mapped —
   **on every path, including the no-check discard path** (spec edge case: duplicates
   fail even when nothing references them). Uniqueness is judged on final labels only —
   an alias colliding with another column's label is a duplicate; two physical columns
   disambiguated by aliases are not.

## Values

Mapped per column while the ResultSet is open:

| Driver returns | Session receives | Post-completion guarantee |
|---|---|---|
| `java.sql.Blob` | `Array[Byte]` | full content readable; locator freed |
| `java.sql.Clob`/`NClob` | `String` | full content readable; locator freed |
| `java.sql.SQLXML` | `String` | full content readable; locator freed |
| `java.sql.Array` | `Vector[Any]`, elements recursively mapped (incl. array-of-LOB) | usable; locator freed |
| anything else | `getObject` value unchanged | current behavior |
| SQL `NULL` | `null` | unchanged |

Failure-path rules: locators are freed in a `finally`-equivalent path even when the
copy throws — the free failure attaches as suppressed to the primary, never replacing
it, never leaking. Detachment failures (including LOB length > `Int.MaxValue`) fail the
operation (KO) — never a partial row.

## Retention

| Checks | `maxRows` | Rows retained | Outcome |
|---|---|---|---|
| present | absent | all (today's behavior) | OK/KO per checks |
| present | set | ≤ n | count > n → KO naming the cap; no truncated data ever reaches checks |
| absent | absent | none (drained, counted) | OK on success; drain runs in a plugin-managed read transaction so PostgreSQL streams (auto-commit off + forward-only + fetch size); timing identical — full execute + transfer still happens |
| absent | set | none | cap enforced while draining — count > n → KO naming the cap (cap is never silently ignored) |

`maxRows` accepts any positive `Int` including `Int.MaxValue` (hint skipped at the
boundary — no overflow); the driver-side `setMaxRows(cap + 1)` hint is best-effort —
correctness always comes from counting. `setLargeMaxRows` is deliberately not used:
drivers without it raise SQLSTATE 0A000 and HikariCP poisons the connection.

## Engine coverage

Alias/case rules verified on H2 **and** PostgreSQL (differing case conventions);
LOB detachment on H2 (`BLOB`/`CLOB`/`NCLOB`/`ARRAY` incl. array-of-LOB) plus PostgreSQL
(`bytea`/`text`/`xml` — PG carries the XML assertion, H2's SQLXML support is weak);
duplicate-label JOIN on H2; 1M-row discard drain on H2 (`SYSTEM_RANGE`).
