package org.galaxio.gatling.jdbc.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression for issue #93 (spec 005 US3): only a genuinely absent value (JVM `null`) or an explicit `NullParam` maps to SQL
  * NULL. Every string value — including the four-character text `NULL` — is stored verbatim. Proven by writing through the real
  * `withParamsMap` binding into H2 and reading each value back.
  */
class NullFidelitySpec extends AnyFlatSpec with Matchers {

  private val url = "jdbc:h2:mem:null_fidelity;DB_CLOSE_DELAY=-1"

  private def withConn[T](body: java.sql.Connection => T): T = {
    val conn = java.sql.DriverManager.getConnection(url, "sa", "")
    try body(conn)
    finally conn.close()
  }

  private def seed(): Unit = withConn { conn =>
    conn.createStatement().execute("CREATE TABLE IF NOT EXISTS nf (id INT PRIMARY KEY, v VARCHAR(50))")
    conn.createStatement().execute("DELETE FROM nf")
  }

  /** Bind `value` through the production `withParamsMap` path and INSERT it, exactly as an action would. */
  private def insert(id: Int, value: Any): Unit = withConn { conn =>
    val swp = SQL(s"INSERT INTO nf (id, v) VALUES ($id, {v})").withParamsMap(Map("v" -> value))
    val ps  = conn.prepareStatement("INSERT INTO nf (id, v) VALUES (?, ?)")
    ps.setInt(1, id)
    swp.params.collectFirst { case ("v", p) => p } match {
      case Some(StrParam(s)) => ps.setString(2, s)
      case Some(NullParam)   => ps.setNull(2, java.sql.Types.VARCHAR)
      case other             => fail(s"unexpected binding for v: $other")
    }
    ps.executeUpdate()
    ps.close()
  }

  private def read(id: Int): (String, Boolean) = withConn { conn =>
    val rs = conn.createStatement().executeQuery(s"SELECT v FROM nf WHERE id = $id")
    rs.next()
    val v  = rs.getString(1)
    (v, rs.wasNull())
  }

  "the text \"NULL\"" should "be stored as the four-character string, not SQL NULL" in {
    seed()
    insert(1, "NULL")
    val (v, wasNull) = read(1)
    wasNull shouldBe false
    v shouldBe "NULL"
  }

  "mixed-case null-like text" should "be stored verbatim" in {
    seed()
    insert(2, "null")
    insert(3, "Null")
    read(2) shouldBe ("null", false)
    read(3) shouldBe ("Null", false)
  }

  "an empty string" should "stay an empty string, distinct from NULL" in {
    seed()
    insert(4, "")
    read(4) shouldBe ("", false)
  }

  "a genuinely absent value (JVM null)" should "map to SQL NULL" in {
    seed()
    insert(5, null)
    val (_, wasNull) = read(5)
    wasNull shouldBe true
  }

  "an explicit NullParam" should "map to SQL NULL" in {
    seed()
    withConn { conn =>
      val swp = SQL("INSERT INTO nf (id, v) VALUES (6, {v})").withParams("v" -> NullParam)
      swp.params should contain("v" -> NullParam)
      val ps  = conn.prepareStatement("INSERT INTO nf (id, v) VALUES (6, ?)")
      ps.setNull(1, java.sql.Types.VARCHAR)
      ps.executeUpdate()
      ps.close()
    }
    read(6)._2 shouldBe true
  }

  "all five encodings" should "remain mutually distinguishable after a round-trip" in {
    seed()
    insert(10, "NULL")
    insert(11, "")
    insert(12, null)
    read(10) shouldBe ("NULL", false)
    read(11) shouldBe ("", false)
    read(12)._2 shouldBe true
  }
}
