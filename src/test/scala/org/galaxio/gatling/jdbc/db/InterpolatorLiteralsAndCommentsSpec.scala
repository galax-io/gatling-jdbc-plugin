package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator.InterpolatorCtx
import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.TryValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

/** Curly braces inside string literals `'…'`, quoted identifiers `"…"`, line comments `--` and block comments `/* … */` reach
  * the driver as written and never become placeholders; `{name}` outside them still becomes ` ?` with the same text and the
  * same name → positions map as before, positions counted over the placeholders outside those zones only.
  */
class InterpolatorLiteralsAndCommentsSpec extends AnyFlatSpec with Matchers with H2ClientSpecFixture {

  override protected val dbName = "interpolator_literals"

  override def beforeAll(): Unit =
    exec("CREATE TABLE IF NOT EXISTS lit_items (id INT PRIMARY KEY, tags VARCHAR(64))")

  private def await[T](future: Future[T]): T = Await.result(future, 10.seconds)

  private def seed(): Unit = {
    exec("DELETE FROM lit_items")
    exec("INSERT INTO lit_items (id, tags) VALUES (1, '{x,y}'), (2, '{z}')")
  }

  private def tagsOf(id: Int): String = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery(s"SELECT tags FROM lit_items WHERE id = $id")
    rs.next()
    rs.getString(1)
  }

  /** The result of a SQL with no placeholder outside the zones: the text as written and nothing to bind. */
  private def untouched(sql: String): InterpolatorCtx = InterpolatorCtx(sql, "", 0, inCurlyBraces = false, Map.empty)

  behavior of "FR-001: the interpolator"

  it should "keep a JSON string literal as written" in {
    val sql = """SELECT '{"a":1}'::jsonb"""
    Interpolator.interpolate(sql) shouldBe untouched(sql)
  }

  it should "keep an array string literal as written" in {
    val sql = "SELECT * FROM t WHERE tags @> '{x,y}'"
    Interpolator.interpolate(sql) shouldBe untouched(sql)
  }

  it should "keep a quoted identifier as written" in {
    val sql = """SELECT "{col}" FROM t"""
    Interpolator.interpolate(sql) shouldBe untouched(sql)
  }

  it should "keep a string literal with a doubled quote as written" in {
    val sql = "SELECT 'it''s {x}'"
    Interpolator.interpolate(sql) shouldBe untouched(sql)
  }

  it should "keep a line comment as written" in {
    val sql = "SELECT 1 -- {note}"
    Interpolator.interpolate(sql) shouldBe untouched(sql)
  }

  it should "keep a block comment as written" in {
    val sql = "SELECT 1 /* {note} */"
    Interpolator.interpolate(sql) shouldBe untouched(sql)
  }

  it should "copy an unclosed literal, identifier or block comment to the end of the SQL" in {
    Seq("SELECT '{x}", """SELECT "{x}""", "SELECT 1 /* {x}").foreach { sql =>
      Interpolator.interpolate(sql) shouldBe untouched(sql)
    }
  }

  it should "close a zone only by the marker of the zone that opened first" in {
    Interpolator.interpolate("SELECT 1 -- it's {x}\n, {y}") shouldBe
      InterpolatorCtx("SELECT 1 -- it's {x}\n,  ?", "", 1, inCurlyBraces = false, Map("y" -> List(1)))
    Interpolator.interpolate("SELECT 'a--{b}' || 'c/*{d}' || {e}") shouldBe
      InterpolatorCtx("SELECT 'a--{b}' || 'c/*{d}' ||  ?", "", 1, inCurlyBraces = false, Map("e" -> List(1)))
  }

  it should "end a block comment at its first */, a nested /* opening nothing" in {
    Interpolator.interpolate("SELECT 1 /* a /* {b} */ {c} */") shouldBe
      InterpolatorCtx("SELECT 1 /* a /* {b} */  ? */", "", 1, inCurlyBraces = false, Map("c" -> List(1)))
  }

  behavior of "FR-001, FR-002: the interpolator on SQL mixing zones and placeholders"

  Seq("LF" -> "\n", "CR" -> "\r").foreach { case (label, eol) =>
    it should s"replace only the placeholders outside the zones, numbered from 1, with a line comment ending in $label" in {
      val sql = "SELECT * FROM t WHERE tags @> '{x,y}' AND id = {id} /* {note} */ -- {c}" + eol + "AND name = {name}"
      Interpolator.interpolate(sql) shouldBe InterpolatorCtx(
        "SELECT * FROM t WHERE tags @> '{x,y}' AND id =  ? /* {note} */ -- {c}" + eol + "AND name =  ?",
        "",
        2,
        inCurlyBraces = false,
        Map("id" -> List(1), "name" -> List(2)),
      )
    }
  }

  behavior of "FR-002: the interpolator outside the zones"

  it should "replace each placeholder with ' ?' and keep its positions newest first, as before" in {
    Interpolator.interpolate("WHERE id = {id} AND name = {name} OR id = {id}") shouldBe InterpolatorCtx(
      "WHERE id =  ? AND name =  ? OR id =  ?",
      "",
      3,
      inCurlyBraces = false,
      Map("id" -> List(3, 1), "name" -> List(2)),
    )
  }

  it should "replace a placeholder right next to a string literal" in {
    Interpolator.interpolate("SELECT 'a'{x}, {y}'b'") shouldBe
      InterpolatorCtx("SELECT 'a' ?,  ?'b'", "", 2, inCurlyBraces = false, Map("x" -> List(1), "y" -> List(2)))
  }

  it should "leave an unclosed brace as the pending name, out of the query, as before" in {
    Interpolator.interpolate("SELECT {abc") shouldBe InterpolatorCtx("SELECT ", "abc", 0, inCurlyBraces = true, Map.empty)
  }

  it should "take quotes, comment markers and braces inside braces as part of the name, as before" in {
    Interpolator.interpolate("SELECT {a'b}, {c--d}, {e{f}") shouldBe InterpolatorCtx(
      "SELECT  ?,  ?,  ?",
      "",
      3,
      inCurlyBraces = false,
      Map("a'b" -> List(1), "c--d" -> List(2), "e{f" -> List(3)),
    )
  }

  behavior of "FR-002: the interpolator next to dialect quoting"

  /** The result of a SQL whose only placeholder is `{id}`, written in it once. */
  private def boundOnce(query: String): InterpolatorCtx =
    InterpolatorCtx(query, "", 1, inCurlyBraces = false, Map("id" -> List(1)))

  it should "bind the placeholder after an Oracle q-quoted literal holding an apostrophe, whatever the prefix case" in {
    Interpolator.interpolate("SELECT q'[it's fine]', {id} FROM DUAL") shouldBe boundOnce("SELECT q'[it's fine]',  ? FROM DUAL")
    Interpolator.interpolate("SELECT nQ'(it's)', {id} FROM DUAL") shouldBe boundOnce("SELECT nQ'(it's)',  ? FROM DUAL")
  }

  it should "bind the placeholder between what PostgreSQL reads as two literals of a type named q" in {
    Interpolator.interpolate("SELECT q'[x', {id}, ']'") shouldBe boundOnce("SELECT q'[x',  ?, ']'")
  }

  it should "bind the placeholder between two PostgreSQL operators holding a backtick" in {
    Interpolator.interpolate("SELECT a <`> {id} <`> b") shouldBe boundOnce("SELECT a <`>  ? <`> b")
  }

  it should "bind the placeholder between two MariaDB columns named with dollars" in {
    Interpolator.interpolate("SELECT $col$, {id}, $col$ FROM t") shouldBe boundOnce("SELECT $col$,  ?, $col$ FROM t")
  }

  it should "bind the placeholder after a plain literal that follows a type name ending in a combining mark and e" in {
    val typeName = s"x${0x301.toChar}e"
    Interpolator.interpolate(s"SELECT $typeName'C:\\', {id}") shouldBe boundOnce(s"SELECT $typeName'C:\\',  ?")
  }

  it should "bind the placeholder after an array subscript whose literal holds a bracket" in {
    Interpolator.interpolate("SELECT ARRAY['a]b', {id}]") shouldBe boundOnce("SELECT ARRAY['a]b',  ?]")
  }

  behavior of "FR-001: JDBCClient on H2"

  it should "return a JSON literal of a select without parameters as written" in {
    val rows = await(client.executeSelect("""SELECT '{"a":1}' AS j""", Seq.empty)(identity)).success.value

    rows shouldBe List(Map("J" -> """{"a":1}"""))
  }

  it should "return a quoted identifier with braces as its column label" in {
    val rows = await(client.executeSelect("""SELECT 1 AS "{col}"""", Seq.empty)(identity)).success.value

    rows shouldBe List(Map("{col}" -> 1))
  }

  it should "insert a JSON literal next to a bound placeholder as written" in {
    seed()
    val insert = """INSERT INTO lit_items (id, tags) VALUES ({id}, '{"a":1}')"""

    await(client.executeUpdate(insert, Seq("id" -> IntParam(3)))(identity)).success.value shouldBe 1
    tagsOf(3) shouldBe """{"a":1}"""
  }

  behavior of "FR-002: JDBCClient on H2"

  it should "bind the placeholder after a literal and comments with braces as the only parameter" in {
    seed()
    val select = "SELECT id FROM lit_items WHERE tags = '{x,y}' /* {note} */ -- {c}\nAND id = {id}"

    await(client.executeSelect(select, Seq("id" -> IntParam(1)))(identity)).success.value shouldBe List(Map("ID" -> 1))
  }
}
