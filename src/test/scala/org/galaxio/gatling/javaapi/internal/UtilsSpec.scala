package org.galaxio.gatling.javaapi.internal

import io.gatling.commons.stats.OK
import io.gatling.commons.validation.Success
import io.gatling.core.session.Session
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Map => JMap}

class UtilsSpec extends AnyFlatSpec with Matchers {

  private def session(attributes: Map[String, Any] = scala.collection.immutable.Map.empty): Session =
    new Session("utils-spec", 1L, attributes, OK, Nil, Session.NothingOnExit, null)

  private def expressionFor(
      expressions: scala.collection.immutable.Seq[
        (String, Session => io.gatling.commons.validation.Validation[Object]),
      ],
      key: String,
  ): Session => io.gatling.commons.validation.Validation[Object] =
    expressions.collectFirst { case (`key`, expression) => expression }.getOrElse(fail(s"Missing expression for '$key'"))

  "Utils.getSeq" should "preserve typed literal values from Java maps" in {
    val expressions = Utils.getSeq(JMap.of("flag", Boolean.box(true), "id", Int.box(2)))

    expressionFor(expressions, "flag")(session()) shouldBe Success(true)
    expressionFor(expressions, "id")(session()) shouldBe Success(2)
  }

  it should "still resolve Gatling EL expressions from Java string values" in {
    val expressions = Utils.getSeq(JMap.of("id", "#{userId}", "name", "Alice"))
    val testSession = session(Map("userId" -> 42))

    expressionFor(expressions, "id")(testSession) shouldBe Success(42)
    expressionFor(expressions, "name")(testSession) shouldBe Success("Alice")
  }
}
