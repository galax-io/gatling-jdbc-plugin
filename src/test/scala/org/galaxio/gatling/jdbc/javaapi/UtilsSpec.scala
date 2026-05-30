package org.galaxio.gatling.jdbc.javaapi

import io.gatling.commons.validation.{Failure, Success}
import io.gatling.core.session.Session
import io.netty.channel.{ChannelFuture, ChannelPromise, EventLoop, EventLoopGroup}
import org.galaxio.gatling.javaapi.internal.Utils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{InvocationHandler, Proxy}
import java.util.{Map => JMap, HashMap => JHashMap, UUID}
import java.util.concurrent.TimeUnit

/** Regression tests for issue #26: Java/Kotlin map-based parameter helpers must preserve original runtime types instead of
  * coercing every value to String via toString().
  */
class UtilsSpec extends AnyFlatSpec with Matchers {

  private val stubEventLoop: EventLoop = Proxy
    .newProxyInstance(
      classOf[EventLoop].getClassLoader,
      Array(classOf[EventLoop]),
      (_: Any, _: java.lang.reflect.Method, _: Array[AnyRef]) => null,
    )
    .asInstanceOf[EventLoop]

  private val emptySession =
    Session("scenario", 0L, stubEventLoop)

  private def resolveValue(values: JMap[String, Object], key: String): Any = {
    val seq  = Utils.getSeq(values)
    val pair = seq.find { case (k, _) => k == key }.getOrElse(fail(s"key '$key' not found"))
    pair._2.apply(emptySession) match {
      case Success(v)   => v
      case Failure(msg) => fail(s"expression failed: $msg")
    }
  }

  "Utils.getSeq" should "preserve Int values as Int (not String)" in {
    val result = resolveValue(JMap.of("id", 42.asInstanceOf[Object]), "id")
    result shouldBe 42
    result should not be "42"
    result shouldBe a[java.lang.Integer]
  }

  it should "preserve Long values as Long (not String)" in {
    val result = resolveValue(JMap.of("n", 123L.asInstanceOf[Object]), "n")
    result shouldBe 123L
    result should not be "123"
    result shouldBe a[java.lang.Long]
  }

  it should "preserve Boolean values as Boolean (not String)" in {
    val result = resolveValue(JMap.of("flag", true.asInstanceOf[Object]), "flag")
    result shouldBe true
    result should not be "true"
    result shouldBe a[java.lang.Boolean]
  }

  it should "preserve UUID values as UUID (not String)" in {
    val id     = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    val result = resolveValue(JMap.of("uid", id.asInstanceOf[Object]), "uid")
    result shouldBe id
    result should not be id.toString
    result shouldBe a[UUID]
  }

  it should "preserve Double values as Double (not String)" in {
    val result = resolveValue(JMap.of("d", 3.14.asInstanceOf[Object]), "d")
    result shouldBe 3.14
    result should not be "3.14"
    result shouldBe a[java.lang.Double]
  }

  it should "preserve null values as null" in {
    val m    = new JHashMap[String, Object]()
    m.put("v", null)
    val seq  = Utils.getSeq(m)
    val pair = seq.find { case (k, _) => k == "v" }.getOrElse(fail("key 'v' not found"))
    pair._2.apply(emptySession) match {
      case Success(v)   => v shouldBe null
      case Failure(msg) => fail(s"expression failed for null: $msg")
    }
  }

  it should "still resolve Gatling EL String expressions (#{...} syntax)" in {
    val session = emptySession.set("myVar", "hello")
    val seq     = Utils.getSeq(JMap.of("col", "#{myVar}".asInstanceOf[Object]))
    val pair    = seq.find { case (k, _) => k == "col" }.get
    pair._2.apply(session) match {
      case Success(v)   => v shouldBe "hello"
      case Failure(msg) => fail(s"EL resolution failed: $msg")
    }
  }

  it should "return plain String values as-is when they contain no EL markers" in {
    val result = resolveValue(JMap.of("name", "Alice".asInstanceOf[Object]), "name")
    result shouldBe "Alice"
  }
}
