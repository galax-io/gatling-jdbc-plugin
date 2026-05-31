package org.galaxio.gatling.jdbc.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDateTime
import java.util.UUID

/** Unit-level regression tests for SQL parameter mapping.
  *
  * Covers `SQL.withParamsMap` type-coercion logic for every `ParamVal` variant. These tests require no database — all
  * assertions are over pure in-memory data structures.
  *
  * Protects against regressions in type-dispatch in `withParamsMap` (wrong ParamVal produced for a Scala type).
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

  // ─── withParams (explicit ParamVal binding) ───────────────────────────────────

  "SQL.withParams" should "preserve explicitly provided ParamVal values unchanged" in {
    val swp = SQL("SELECT {x}").withParams("x" -> StrParam("raw"))
    swp.params should contain("x" -> StrParam("raw"))
  }

  it should "allow explicit NullParam without using the 'NULL' sentinel string" in {
    val swp = SQL("SELECT {x}").withParams("x" -> NullParam)
    swp.params should contain("x" -> NullParam)
  }

  // ─── withOutParams ────────────────────────────────────────────────────────────

  "SqlWithParam.withOutParams" should "store out-parameter definitions for callable statements" in {
    val base = SQL("CALL proc({p})").withParams("p" -> IntParam(1))
    val swp  = base.withOutParams(Seq("result" -> java.sql.Types.INTEGER))
    swp.outParams should contain("result" -> java.sql.Types.INTEGER)
    swp.params should contain("p" -> IntParam(1))
  }
}
