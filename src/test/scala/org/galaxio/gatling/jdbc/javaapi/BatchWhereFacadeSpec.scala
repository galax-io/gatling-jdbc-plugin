package org.galaxio.gatling.jdbc.javaapi

import org.galaxio.gatling.javaapi.actions.{BatchUpdateBaseAction, BatchUpdateValuesStepAction}
import org.galaxio.gatling.jdbc.actions.actions.{BatchParameterizedUpdateAction, BatchUpdateAction}
import org.galaxio.gatling.jdbc.internal.BatchBase
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Map => JMap}

/** Facade coverage for issue #125 (spec 005 US1): the Java `where(String)` / `where(String, Map)` overloads reach the Scala
  * carriers, enforce the same construction-time EL rejection, and unwrap through `BatchBase.toScalaBatch`.
  */
class BatchWhereFacadeSpec extends AnyFlatSpec with Matchers {

  private def valuesStep(): BatchUpdateValuesStepAction =
    new BatchUpdateBaseAction(
      new org.galaxio.gatling.jdbc.actions.actions.BatchUpdateBaseAction(_ => io.gatling.commons.validation.Success("T")),
    )
      .set(JMap.of("NAME", "X"))

  "the Java static where(String) overload" should "produce a plain BatchUpdateAction the batch unwrapper accepts" in {
    val action = valuesStep().where("ID = 2")
    BatchBase.toScalaBatch(action) shouldBe a[BatchUpdateAction]
  }

  "the Java parameterized where(String, Map) overload" should "produce a BatchParameterizedUpdateAction the batch unwrapper accepts" in {
    val action = valuesStep().where("EMAIL = {email}", JMap.of("email", "#{userEmail}"))
    val scala  = BatchBase.toScalaBatch(action)
    scala shouldBe a[BatchParameterizedUpdateAction]
    scala.asInstanceOf[BatchParameterizedUpdateAction].whereClause shouldBe "EMAIL = {email}"
  }

  "the Java where(String) overload with Gatling EL" should "be rejected at construction" in {
    intercept[IllegalArgumentException] {
      valuesStep().where("EMAIL = '#{userEmail}'")
    }
  }
}
