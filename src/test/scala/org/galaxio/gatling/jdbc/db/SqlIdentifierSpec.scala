package org.galaxio.gatling.jdbc.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Grammar unit spec for issue #124 (US6) — the accept/reject table from the identifier-grammar contract. */
class SqlIdentifierSpec extends AnyFlatSpec with Matchers {

  private val accepted = Seq(
    "users",                  // plain segment
    "public.users",           // schema-qualified
    "cat.public.users",       // 3 segments
    "_tmp$2",                 // valid unquoted chars
    "a" * 128,                // unquoted length boundary
    """"Order Details"""",    // ANSI-quoted, space is data
    "\"say \"\"hi\"\"\"",     // doubled-quote escape
    "`weird-name`",           // backtick-quoted
    "`tick``inside`",         // doubled-backtick escape
    """"dots.are.data"""",    // dots inside quotes are not separators
    """public."Weird Name"""", // mixed unquoted + quoted segments
  )

  private val rejected = Seq(
    "users; DROP TABLE t", // whitespace / ';' outside quotes
    "users--",             // '-' not a word-char
    "users\"",             // unbalanced quote
    "\"\"",                // empty quoted body
    "``",                  // empty backtick body
    "a.b.c.d",             // > 3 segments
    "a" * 129,             // over the unquoted length cap
    "us\u0000ers",         // NUL
    "\"a}b\"",             // brace in quoted body — placeholder-collision rule
    "`a{b`",               // brace in backtick body
    "1users",              // leading digit
    "",                    // empty
    " users",              // leading whitespace
    "users ",              // trailing whitespace
    "users.",              // trailing dot
    ".users",              // leading dot
    "a..b",                // empty middle segment
    """"a" extra""",       // garbage after closing quote
  )

  "SqlIdentifier.isValid" should "accept every documented valid form" in {
    accepted.foreach { value =>
      withClue(s"expected accept: <$value> ") {
        SqlIdentifier.isValid(value) shouldBe true
      }
    }
  }

  it should "reject every documented invalid form" in {
    rejected.foreach { value =>
      withClue(s"expected reject: <$value> ") {
        SqlIdentifier.isValid(value) shouldBe false
      }
    }
  }

  "SqlIdentifier.validate" should "quote the rejected value and the accepted forms in the failure" in {
    val error = SqlIdentifier.validate("users; DROP TABLE t").left.toOption.get

    error.getMessage should include("users; DROP TABLE t")
    error.getMessage should include("unquoted")
    error.getMessage should include("3 segments")
  }

  it should "return the value unchanged on success" in {
    SqlIdentifier.validate("public.users") shouldBe Right("public.users")
  }
}
