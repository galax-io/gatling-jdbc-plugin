# Contract: SQL Identifier Grammar

**Feature**: `003-batch-resultset-correctness` | Implements FR-008 (#124), decision R7 (revised 2026-07-21)

Applied by `SqlIdentifier.validate` to **table names** (session/feeder-resolved
`Expression[String]` — the dynamic, injectable input) and to **static column names**
(`Columns(...)`, batch-update SET keys — fixed `String`s at build time, validated as a
typo/codegen guard; not feeder-reachable with the current published API) in insert,
batch-insert, and batch-update actions, before any SQL text is assembled. Not applied to
`where(...)` fragments or `rawSql` (user-authored SQL by contract).

## Grammar (EBNF)

```ebnf
identifier   = segment , { "." , segment } ;   (* max 3 segments: catalog.schema.object *)
segment      = unquoted | ansi-quoted | backtick-quoted ;
unquoted     = letter-or-underscore , { word-char } ;      (* 1–128 chars total *)
ansi-quoted  = '"' , { quoted-char | '""' } , '"' ;        (* non-empty body *)
backtick-quoted = "`" , { btick-char | "``" } , "`" ;      (* non-empty body *)

letter-or-underscore = "A"…"Z" | "a"…"z" | "_" ;
word-char            = letter-or-underscore | "0"…"9" | "$" ;
quoted-char          = any char except '"' , NUL , "{" , "}" ;
btick-char           = any char except "`" , NUL , "{" , "}" ;
```

Rules:

- Whole value must match — no leading/trailing whitespace, no trailing characters.
- Quoted segments: the only escape is doubling the quote character; NUL is rejected.
- **`{` and `}` are rejected everywhere, including quoted bodies**: insert/update SQL
  derives `{column}` parameter placeholders from column names, and a brace inside an
  identifier would terminate the interpolator's placeholder early and produce malformed
  SQL. Lifting this would require synthetic binding names decoupled from identifiers —
  deliberately not built (R7).
- Segment count 1–3, joined by literal `.` (dots inside quoted segments are data, not separators).
- Unquoted segments capped at 128 chars (covers every mainstream engine's limit); the
  128-char boundary is an accepted case, 129 is rejected.

## Accept / reject examples

| Input | Verdict | Why |
|---|---|---|
| `users` | accept | plain segment |
| `public.users` | accept | schema-qualified |
| `cat.public.users` | accept | 3 segments |
| `_tmp$2` | accept | valid unquoted chars |
| (128 × `a`) | accept | at the unquoted length boundary |
| `"Order Details"` | accept | ANSI-quoted, space is data |
| `"say ""hi"""` | accept | doubled-quote escape |
| `` `weird-name` `` | accept | backtick-quoted (MySQL-style) |
| `users; DROP TABLE t` | reject | whitespace/`;` outside quotes |
| `users--` | reject | `-` not a word-char |
| `users"` | reject | unbalanced quote |
| `""` | reject | empty quoted body |
| `"a}b"` | reject | brace in quoted body — placeholder-collision rule |
| `a.b.c.d` | reject | >3 segments |
| (129 × `a`) | reject | unquoted length cap |
| `us﻿ers` (any control/NUL) | reject | not in alphabet |

## Failure contract

Rejection produces a per-request KO (via the action's `Validation.Failure` →
`crashOnFailure` path) whose message quotes the rejected value and states the accepted
forms. **No SQL string is built and nothing is sent to the database** for that request;
other virtual users are unaffected.

## Documented quoting policy (user-facing)

Plain names and `schema.table` need nothing. Names with spaces, punctuation, reserved
words, or case-significant spelling must be passed quoted — ANSI `"…"` (portable) or
backtick `` `…` `` (MySQL-family) — except `{`/`}`, which identifiers may not contain.
The plugin passes quoted segments through verbatim; case semantics inside quotes are the
engine's.
