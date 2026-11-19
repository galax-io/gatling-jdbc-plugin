package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator
import org.galaxio.gatling.jdbc.db.JDBCClient.Interpolator.InterpolatorCtx
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** SqlIdentifier accepts a backtick-quoted column whose body holds a quote or a comment marker; the INSERT the plugin builds
  * from such a column must still turn its `{column}` placeholder into ` ?`.
  */
class InterpolatorBacktickIdentifierSpec extends AnyFlatSpec with Matchers {

  /** The INSERT text DBInsertAction and DBBatchAction build from a validated column name. */
  private def insertSql(column: String): String = s"INSERT INTO t ($column) VALUES({$column})"

  "the interpolator" should "replace the placeholder of a backtick-quoted column holding a quote or a comment marker" in {
    Seq("`it's`", "`a\"b`", "`a--b`", "`a/*b`").foreach { column =>
      withClue(s"column $column: ") {
        Interpolator.interpolate(insertSql(column)) shouldBe
          InterpolatorCtx(s"INSERT INTO t ($column) VALUES( ?)", "", 1, inCurlyBraces = false, Map(column -> List(1)))
      }
    }
  }
}
