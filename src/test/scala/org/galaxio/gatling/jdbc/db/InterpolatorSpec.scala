package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InterpolatorSpec extends AnyFlatSpec with Matchers {

  "Interpolator" should "replace single param with ?" in {
    val ctx = Interpolator.interpolate("SELECT * FROM t WHERE id = {id}")
    ctx.queryString shouldBe "SELECT * FROM t WHERE id =  ?"
    ctx.m should contain key "id"
    ctx.m("id") shouldBe List(1)
  }

  it should "replace multiple params" in {
    val ctx = Interpolator.interpolate("INSERT INTO t (a, b) VALUES ({a}, {b})")
    ctx.queryString shouldBe "INSERT INTO t (a, b) VALUES ( ?,  ?)"
    ctx.m should have size 2
  }

  it should "handle repeated params" in {
    val ctx = Interpolator.interpolate("SELECT * FROM t WHERE a = {x} OR b = {x}")
    ctx.m("x") should have size 2
  }

  it should "return original SQL when no params" in {
    val ctx = Interpolator.interpolate("SELECT * FROM t")
    ctx.queryString shouldBe "SELECT * FROM t"
    ctx.m shouldBe empty
  }

  it should "handle empty string" in {
    val ctx = Interpolator.interpolate("")
    ctx.queryString shouldBe ""
    ctx.m shouldBe empty
  }

  it should "handle special characters in SQL" in {
    val ctx = Interpolator.interpolate("SELECT * FROM t WHERE name LIKE '%test%' AND id = {id}")
    ctx.queryString should include("%test%")
    ctx.m should contain key "id"
  }

  "SubstituteParams" should "replace params with literal values" in {
    val sql    = SqlWithParam(
      "INSERT INTO t (name, age) VALUES ({name}, {age})",
      Seq("name" -> StrParam("Alice"), "age" -> IntParam(30)),
    )
    val result = sql.substituteParams
    result should include("'Alice'")
    result should include("30")
  }

  it should "handle NULL values" in {
    val sql = SqlWithParam(
      "INSERT INTO t (name) VALUES ({name})",
      Seq("name" -> NullParam),
    )
    sql.substituteParams should include("NULL")
  }

  it should "handle empty params" in {
    val sql = SqlWithParam("SELECT 1", Seq.empty)
    sql.substituteParams shouldBe "SELECT 1"
  }
}
