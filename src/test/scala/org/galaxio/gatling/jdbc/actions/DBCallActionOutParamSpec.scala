package org.galaxio.gatling.jdbc.actions

import org.galaxio.gatling.jdbc.db
import org.galaxio.gatling.jdbc.db.JDBCClient
import org.galaxio.gatling.jdbc.db.testsupport.H2
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Types
import java.util.concurrent.Executors
import scala.collection.immutable.Map
import scala.concurrent.Future
import scala.util.{Success, Try}

/** Tests for stored procedure OUT parameter support (issue #28).
  *
  * Uses a stub JDBCClient subclass to inject pre-canned OUT parameter results into the action, which lets us verify the
  * session-update logic in DBCallAction without depending on a database that natively supports OUT parameters.
  *
  * Additionally tests that JDBCClient.call now accepts a Map[String, Any] => U success callback (API-contract check).
  */
class DBCallActionOutParamSpec extends AnyFlatSpec with Matchers with JdbcActionSpecFixture {

  // ─── Stub JDBCClient that bypasses JDBC entirely ─────────────────────────────

  /** A JDBCClient that, when `call` is invoked, immediately fires the success callback with a pre-configured map of OUT
    * parameter values.
    */
  private def stubClientWith(outResults: Map[String, Any]): JDBCClient = {
    val ds           = H2.dataSource("out_param_stub", 1)
    val blockingPool = Executors.newFixedThreadPool(1)

    new JDBCClient(ds, blockingPool) {
      override def call[U](sqlCall: String, params: Seq[(String, db.ParamVal)], outParams: Seq[(String, Int)])(
          consumer: Try[Map[String, Any]] => U,
      ): Future[U] = Future.successful(consumer(Success(outResults)))
    }
  }

  private def buildTestContext(outResults: Map[String, Any]): TestContext =
    buildTestContextWithClient(stubClientWith(outResults), config)

  // ─── tests ───────────────────────────────────────────────────────────────────

  "DBCallAction" should "store OUT parameter values returned by the database into the Gatling session" in {
    val outResults = Map[String, Any]("myOut" -> Integer.valueOf(42), "anotherOut" -> "hello")
    val tc         = buildTestContext(outResults)
    val capture    = new CaptureAction()

    try {
      val session = freshSession(userId = 1L)

      val action = DBCallAction(
        requestName = _ => io.gatling.commons.validation.Success("call-out-param-request"),
        procedureName = _ => io.gatling.commons.validation.Success("MY_PROC"),
        next = capture,
        ctx = tc.ctx,
        sessionParams = Seq.empty,
        outParams = Seq("myOut" -> Types.INTEGER, "anotherOut" -> Types.VARCHAR),
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      capture.capturedSession.attributes("myOut") shouldBe Integer.valueOf(42)
      capture.capturedSession.attributes("anotherOut") shouldBe "hello"
    } finally {
      tc.close()
    }
  }

  it should "not add any extra session attributes when no OUT parameters are declared" in {
    val tc      = buildTestContext(Map.empty)
    val capture = new CaptureAction()

    try {
      val session = freshSession(userId = 2L)

      val action = DBCallAction(
        requestName = _ => io.gatling.commons.validation.Success("call-no-out-params"),
        procedureName = _ => io.gatling.commons.validation.Success("MY_PROC"),
        next = capture,
        ctx = tc.ctx,
        sessionParams = Seq.empty,
        outParams = Seq.empty,
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      capture.capturedSession.attributes shouldBe empty
    } finally {
      tc.close()
    }
  }

  it should "overwrite an existing session attribute when an OUT parameter name matches it" in {
    val outResults = Map[String, Any]("existingKey" -> Integer.valueOf(99))
    val tc         = buildTestContext(outResults)
    val capture    = new CaptureAction()

    try {
      val session = freshSession(userId = 3L, attributes = Map("existingKey" -> "old-value"))

      val action = DBCallAction(
        requestName = _ => io.gatling.commons.validation.Success("call-overwrite"),
        procedureName = _ => io.gatling.commons.validation.Success("MY_PROC"),
        next = capture,
        ctx = tc.ctx,
        sessionParams = Seq.empty,
        outParams = Seq("existingKey" -> Types.INTEGER),
      )

      action.execute(session)

      capture.awaitCapture() shouldBe true
      capture.capturedSession.isFailed shouldBe false
      capture.capturedSession.attributes("existingKey") shouldBe Integer.valueOf(99)
    } finally {
      tc.close()
    }
  }
}
