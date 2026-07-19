package org.galaxio.gatling.jdbc.actions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression tests for session.markAsFailed behaviour (issue #27).
  *
  * All five JDBC action files (DBQueryAction, DBInsertAction, DBBatchAction, DBCallAction, DBRawQueryAction) call
  * session.markAsFailed before forwarding the session to executeNext when a JDBC operation returns KO. These tests document and
  * protect that contract at the Session level.
  */
class SessionMarkAsFailedSpec extends AnyFlatSpec with Matchers with SessionFixture {

  "Session.markAsFailed" should "set isFailed to true on a fresh (OK) session" in {
    val session = freshSession()
    session.isFailed shouldBe false

    val failed = session.markAsFailed
    failed.isFailed shouldBe true
  }

  it should "be idempotent — calling markAsFailed twice still yields isFailed true" in {
    val session = freshSession()
    val failed  = session.markAsFailed.markAsFailed
    failed.isFailed shouldBe true
  }

  it should "not mutate the original session (immutability contract)" in {
    val original = freshSession()
    val _        = original.markAsFailed
    original.isFailed shouldBe false
  }

  it should "return a new Session instance that preserves scenario and userId" in {
    val original = freshSession()
    val failed   = original.markAsFailed

    failed.scenario shouldBe original.scenario
    failed.userId shouldBe original.userId
  }
}
