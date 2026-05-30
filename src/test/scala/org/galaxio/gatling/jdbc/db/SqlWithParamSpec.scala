package org.galaxio.gatling.jdbc.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDateTime
import java.util.UUID

/** Unit-level regression tests for SQL interpolation and parameter mapping.
  *
  * Covers `SQL.withParamsMap` type-coercion logic and `SqlWithParam.substituteParams`
  * string-rendering for every `ParamVal` variant.  These tests require no database — all
  * assertions are over pure in-memory data structures.
  *
  * Protects against regressions in:
  *   - type-dispatch in `withParamsMap` (wrong ParamVal produced for a Scala type)
  *   - SQL-rendering in `paramValueToSql` (wrong SQL fragment for a ParamVal variant)
  *   - placeholder parsing in `substituteParams` (brace delimiters, whitespace trimming)
  */
class SqlWithParamSpec extends AnyFlatSpec with Matchers {

  // ─── withParamsMap type-coercion ─────────────────────────────────────────────

  "SQL.withParamsMap" should "map Int values to IntParam" in {
    val swp = SQL("SELECT {n}").withParamsMap(Map("n" -> 42))
    swp.params should contain("n" -> IntParam(42))
  }

  it should "map Long values to LongParam" in {
    val swp = SQL("SELECT {n}").withParamsMap(Map("n" -> 42L))
    swp.params should contain("n" -> LongParam(42L))
  }

  it should "map Double values to DoubleParam" in {
    val swp = SQL("SELECT {n}").withParamsMap(Map("n" -> 3.14))
    swp.params should contain("n" -> DoubleParam(3.14))
  }

  it should "map Boolean values to BooleanParam" in {
    val swp = SQL("SELECT {flag}").withParamsMap(Map("flag" -> true))
    swp.params should contain("flag" -> BooleanParam(true))
  }

  it should "map String values to StrParam" in {
    val swp = SQL("SELECT {s}").withParamsMap(Map("s" -> "hello"))
    swp.params should contain("s" -> StrParam("hello"))
  }

  it should "map LocalDateTime values to DateParam" in {
    val dt  = LocalDateTime.of(2024, 1, 15, 10, 30, 0)
    val swp = SQL("SELECT {d}").withParamsMap(Map("d" -> dt))
    swp.params should contain("d" -> DateParam(dt))
  }

  it should "map UUID values to UUIDParam" in {
    val id  = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    val swp = SQL("SELECT {id}").withParamsMap(Map("id" -> id))
    swp.params should contain("id" -> UUIDParam(id))
  }

  it should "map null to NullParam" in {
    val swp = SQL("SELECT {x}").withParamsMap(Map("x" -> null))
    swp.params should contain("x" -> NullParam)
  }

  it should "map the sentinel string 'NULL' to NullParam" in {
    val swp = SQL("SELECT {x}").withParamsMap(Map("x" -> "NULL"))
    swp.params should contain("x" -> NullParam)
  }

  it should "fall back to StrParam(toString) for unknown types" in {
    case class Custom(v: Int) { override def toString: String = s"custom-$v" }
    val swp = SQL("SELECT {x}").withParamsMap(Map("x" -> Custom(7)))
    swp.params should contain("x" -> StrParam("custom-7"))
  }

  it should "handle multiple parameters of mixed types in one call" in {
    val swp = SQL("INSERT INTO t (a,b,c) VALUES ({a},{b},{c})")
      .withParamsMap(Map("a" -> 1, "b" -> "two", "c" -> true))
    swp.params.toMap should contain allOf (
      "a" -> IntParam(1),
      "b" -> StrParam("two"),
      "c" -> BooleanParam(true),
    )
  }

  // ─── substituteParams SQL rendering ──────────────────────────────────────────

  "SqlWithParam.substituteParams" should "render IntParam as a bare integer literal" in {
    val result = SQL("SELECT {n}").withParamsMap(Map("n" -> 42)).substituteParams
    result.trim shouldBe "SELECT  42"
  }

  it should "render LongParam as a bare long literal" in {
    val result = SQL("SELECT {n}").withParamsMap(Map("n" -> 100L)).substituteParams
    result.trim shouldBe "SELECT  100"
  }

  it should "render DoubleParam as a bare double literal" in {
    val result = SQL("WHERE v > {v}").withParamsMap(Map("v" -> 1.5)).substituteParams
    result.trim shouldBe "WHERE v >  1.5"
  }

  it should "render StrParam wrapped in single quotes" in {
    val result = SQL("WHERE name = {name}").withParamsMap(Map("name" -> "alice")).substituteParams
    result.trim shouldBe "WHERE name =  'alice'"
  }

  it should "render NullParam as the keyword NULL" in {
    val result = SQL("WHERE x = {x}").withParamsMap(Map("x" -> null)).substituteParams
    result.trim shouldBe "WHERE x =  NULL"
  }

  it should "render BooleanParam as a bare boolean literal" in {
    val resultTrue  = SQL("{f}").withParamsMap(Map("f" -> true)).substituteParams.trim
    val resultFalse = SQL("{f}").withParamsMap(Map("f" -> false)).substituteParams.trim
    resultTrue  shouldBe "true"
    resultFalse shouldBe "false"
  }

  it should "render DateParam as a CAST TIMESTAMP expression" in {
    val dt     = LocalDateTime.of(2024, 6, 1, 12, 0, 0)
    val result = SQL("WHERE ts = {ts}").withParamsMap(Map("ts" -> dt)).substituteParams
    result should include("CAST(")
    result should include("AS TIMESTAMP)")
    result should include("2024-06-01")
  }

  it should "render UUIDParam as a CAST UUID expression" in {
    val id     = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    val result = SQL("WHERE id = {id}").withParamsMap(Map("id" -> id)).substituteParams
    result should include("CAST(")
    result should include("AS UUID)")
    result should include(id.toString)
  }

  it should "render an empty string for an unknown placeholder name" in {
    // A placeholder that has no matching param key renders as an empty string fragment
    val sql    = SqlWithParam("SELECT {missing}", params = Seq.empty)
    val result = sql.substituteParams
    result should not include "{missing}"
  }

  it should "substitute multiple placeholders in a single query" in {
    val result = SQL("INSERT INTO t (a,b) VALUES ({a},{b})")
      .withParamsMap(Map("a" -> 1, "b" -> "two"))
      .substituteParams
    result should include("1")
    result should include("'two'")
  }

  // ─── withParams (explicit ParamVal binding) ───────────────────────────────────

  "SQL.withParams" should "preserve explicitly provided ParamVal values unchanged" in {
    val swp = SQL("SELECT {x}").withParams("x" -> StrParam("raw"))
    swp.params should contain("x" -> StrParam("raw"))
  }

  it should "allow explicit NullParam without using the 'NULL' sentinel string" in {
    val swp = SQL("SELECT {x}").withParams("x" -> NullParam)
    swp.params should contain("x" -> NullParam)
    swp.substituteParams should include("NULL")
  }

  // ─── withOutParams ────────────────────────────────────────────────────────────

  "SqlWithParam.withOutParams" should "store out-parameter definitions for callable statements" in {
    val base = SQL("CALL proc({p})").withParams("p" -> IntParam(1))
    val swp  = base.withOutParams(Seq("result" -> java.sql.Types.INTEGER))
    swp.outParams should contain("result" -> java.sql.Types.INTEGER)
    swp.params    should contain("p"      -> IntParam(1))
  }
}
