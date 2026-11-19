package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator.InterpolatorCtx
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** In a PostgreSQL escape string `E'…'` a backslash escapes the quote: the literal ends at its unescaped quote, and a
  * placeholder after it is still a placeholder.
  */
class InterpolatorEscapeStringSpec extends AnyFlatSpec with Matchers {

  "the interpolator" should "replace a placeholder after a PostgreSQL escape string holding an escaped quote" in {
    Interpolator.interpolate("""SELECT E'it\'s', {x}""") shouldBe
      InterpolatorCtx("""SELECT E'it\'s',  ?""", "", 1, inCurlyBraces = false, Map("x" -> List(1)))
    Interpolator.interpolate("""SELECT e'\'' AS q, {x}""") shouldBe
      InterpolatorCtx("""SELECT e'\'' AS q,  ?""", "", 1, inCurlyBraces = false, Map("x" -> List(1)))
  }
}
