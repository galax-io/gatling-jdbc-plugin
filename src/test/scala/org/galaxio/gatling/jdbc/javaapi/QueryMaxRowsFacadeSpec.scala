package org.galaxio.gatling.jdbc.javaapi

import io.gatling.commons.validation.Success
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Facade coverage for issue #86 (US4): the Java `maxRows(int)` passthrough reaches the Scala builder and keeps the
  * copy-on-write contract (#80).
  */
class QueryMaxRowsFacadeSpec extends AnyFlatSpec with Matchers {

  private def newBase(): org.galaxio.gatling.javaapi.actions.QueryActionBuilder =
    new org.galaxio.gatling.javaapi.actions.QueryActionBuilder(
      org.galaxio.gatling.jdbc.actions.actions
        .QueryActionBuilder(_ => Success("cap-request"), _ => Success("SELECT 1"), params = Seq.empty),
    )

  private def scalaBuilderOf(
      b: org.galaxio.gatling.javaapi.actions.QueryActionBuilder,
  ): org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder =
    b.asScala().asInstanceOf[org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder]

  "javaapi QueryActionBuilder.maxRows" should "pass the cap through to the Scala builder" in {
    val base   = newBase()
    val capped = base.maxRows(5)

    capped should not be theSameInstanceAs(base)
    scalaBuilderOf(capped).maxRows shouldBe Some(5)
  }

  it should "leave the base builder unmodified (copy-on-write)" in {
    val base = newBase()
    base.maxRows(5)

    scalaBuilderOf(base).maxRows shouldBe None
  }

  it should "reject a non-positive cap" in {
    an[IllegalArgumentException] should be thrownBy newBase().maxRows(0)
    an[IllegalArgumentException] should be thrownBy newBase().maxRows(-1)
  }
}
