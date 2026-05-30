package org.galaxio.gatling.jdbc.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class ResourceFutSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private def awaitFailed[A](f: Future[A]): Throwable =
    Await.ready(f, 5.seconds).value.get.failed.get

  "ResourceFut.make" should "propagate the operation exception when release succeeds" in {
    val opException = new RuntimeException("op failed")
    val resource    = ResourceFut.make(Future.successful("res"))(_ => Future.successful(()))
    val result      = resource.use(_ => Future.failed(opException))

    val thrown = awaitFailed(result)
    thrown shouldBe opException
    thrown.getSuppressed.length shouldBe 0
  }

  it should "propagate the operation exception with release exception as suppressed on double failure" in {
    val opException      = new RuntimeException("op failed")
    val releaseException = new RuntimeException("release failed")
    val resource         = ResourceFut.make(Future.successful("res"))(_ => Future.failed(releaseException))
    val result           = resource.use(_ => Future.failed(opException))

    val thrown = awaitFailed(result)
    thrown shouldBe opException
    thrown.getSuppressed should have length 1
    thrown.getSuppressed.head shouldBe releaseException
  }

  it should "propagate the release exception when operation succeeds but release fails" in {
    val releaseException = new RuntimeException("release failed")
    val resource         = ResourceFut.make(Future.successful("res"))(_ => Future.failed(releaseException))
    val result           = resource.use(_ => Future.successful(42))

    val thrown = awaitFailed(result)
    thrown shouldBe releaseException
  }
}
