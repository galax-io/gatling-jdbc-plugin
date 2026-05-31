package org.galaxio.gatling.jdbc.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

/** Unit-level regression tests for the ResourceFut resource-lifecycle abstraction.
  *
  * Covers:
  *   - Happy-path acquire → use → release
  *   - Acquire failure (resource never handed to the user, release not called)
  *   - Operation failure with successful release (primary exception propagated, no suppressed)
  *   - Operation failure with release failure (primary propagated, release added as suppressed)
  *   - Release failure when operation succeeds (release exception propagated)
  *   - Release is always called exactly once, even on failure
  *   - ResourceFut.pure and ResourceFut.liftFuture smart constructors
  *   - map and flatMap combinators
  *
  * All tests are in-memory and require no external services.
  */
class ResourceFutSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private def awaitFailed[A](f: Future[A]): Throwable =
    Await.ready(f, 5.seconds).value.get.failed.get

  // ─── ResourceFut.make happy path ─────────────────────────────────────────────

  "ResourceFut.make" should "return the result of the use-function on success" in {
    val resource = ResourceFut.make(Future.successful("res"))(_ => Future.successful(()))
    val result   = await(resource.use(r => Future.successful(r.length)))
    result shouldBe 3
  }

  it should "call release exactly once after a successful use" in {
    val releaseCount = new AtomicInteger(0)
    val resource     =
      ResourceFut.make(Future.successful("res"))(_ => Future.successful(releaseCount.incrementAndGet()).map(_ => ()))
    await(resource.use(_ => Future.successful(42)))
    releaseCount.get() shouldBe 1
  }

  it should "call release exactly once when the use-function fails" in {
    val releaseCount = new AtomicInteger(0)
    val resource     =
      ResourceFut.make(Future.successful("res"))(_ => Future.successful(releaseCount.incrementAndGet()).map(_ => ()))
    awaitFailed(resource.use(_ => Future.failed(new RuntimeException("op failed"))))
    releaseCount.get() shouldBe 1
  }

  // ─── failure semantics ───────────────────────────────────────────────────────

  it should "propagate the operation exception when release succeeds" in {
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

  it should "not add the release exception as suppressed when it is the same object as the op exception" in {
    val singleException = new RuntimeException("same exception")
    val resource        = ResourceFut.make(Future.successful("res"))(_ => Future.failed(singleException))
    val result          = resource.use(_ => Future.failed(singleException))

    val thrown = awaitFailed(result)
    thrown shouldBe singleException
    // release exception is the same object — must NOT be added as suppressed (guarded by `ne` check)
    thrown.getSuppressed.length shouldBe 0
  }

  it should "propagate the release exception when operation succeeds but release fails" in {
    val releaseException = new RuntimeException("release failed")
    val resource         = ResourceFut.make(Future.successful("res"))(_ => Future.failed(releaseException))
    val result           = resource.use(_ => Future.successful(42))

    val thrown = awaitFailed(result)
    thrown shouldBe releaseException
  }

  it should "fail with the acquire exception and not call release when acquire fails" in {
    val acquireException = new RuntimeException("acquire failed")
    val releaseCount     = new AtomicInteger(0)
    val resource         = ResourceFut.make(Future.failed[String](acquireException))(_ =>
      Future.successful(releaseCount.incrementAndGet()).map(_ => ()),
    )
    val result           = resource.use(_ => Future.successful("should not run"))

    val thrown = awaitFailed(result)
    thrown shouldBe acquireException
    releaseCount.get() shouldBe 0
  }

  // ─── ResourceFut.pure ────────────────────────────────────────────────────────

  "ResourceFut.pure" should "wrap a value and make it available to the use-function" in {
    val resource = ResourceFut.pure("value")
    val result   = await(resource.use(v => Future.successful(v.toUpperCase)))
    result shouldBe "VALUE"
  }

  it should "propagate a use-function failure without masking it" in {
    val ex       = new RuntimeException("pure-use failed")
    val resource = ResourceFut.pure("value")
    val thrown   = awaitFailed(resource.use(_ => Future.failed(ex)))
    thrown shouldBe ex
  }

  // ─── ResourceFut.liftFuture ──────────────────────────────────────────────────

  "ResourceFut.liftFuture" should "make the future's value available to the use-function" in {
    val resource = ResourceFut.liftFuture(Future.successful(99))
    val result   = await(resource.use(n => Future.successful(n * 2)))
    result shouldBe 198
  }

  it should "propagate a failure from the lifted future" in {
    val ex       = new RuntimeException("lifted future failed")
    val resource = ResourceFut.liftFuture(Future.failed[Int](ex))
    val thrown   = awaitFailed(resource.use(n => Future.successful(n * 2)))
    thrown shouldBe ex
  }

  it should "propagate a use-function failure" in {
    val ex       = new RuntimeException("use failed after lift")
    val resource = ResourceFut.liftFuture(Future.successful(1))
    val thrown   = awaitFailed(resource.use(_ => Future.failed(ex)))
    thrown shouldBe ex
  }

  // ─── map combinator ──────────────────────────────────────────────────────────

  "ResourceFut.map" should "transform the resource value before handing it to use" in {
    import ResourceFut.ResourceFutOps
    val resource = ResourceFut.pure(10).map(_ * 3)
    val result   = await(resource.use(n => Future.successful(n)))
    result shouldBe 30
  }

  it should "propagate a use-function failure through the mapped resource" in {
    import ResourceFut.ResourceFutOps
    val ex       = new RuntimeException("map-use failed")
    val resource = ResourceFut.pure("hello").map(_.length)
    val thrown   = awaitFailed(resource.use(_ => Future.failed(ex)))
    thrown shouldBe ex
  }

  // ─── flatMap combinator ──────────────────────────────────────────────────────

  "ResourceFut.flatMap" should "chain two resources and make the inner value available to use" in {
    import ResourceFut.ResourceFutOps
    val outer  = ResourceFut.pure("hello")
    val inner  = outer.flatMap(s => ResourceFut.pure(s.length))
    val result = await(inner.use(n => Future.successful(n)))
    result shouldBe 5
  }

  it should "propagate a failure from the inner resource factory" in {
    import ResourceFut.ResourceFutOps
    val ex     = new RuntimeException("inner resource failed")
    val outer  = ResourceFut.pure("hello")
    val inner  = outer.flatMap(_ => ResourceFut.liftFuture(Future.failed[Int](ex)))
    val thrown = awaitFailed(inner.use(n => Future.successful(n)))
    thrown shouldBe ex
  }
}
