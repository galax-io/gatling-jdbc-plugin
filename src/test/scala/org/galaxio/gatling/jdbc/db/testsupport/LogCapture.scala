package org.galaxio.gatling.jdbc.db.testsupport

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

import java.util.concurrent.CopyOnWriteArrayList

/** Test-only logback capture: attach an in-memory appender to named loggers, force them to a level, run a block, and return
  * every line the code under test logged. Used by the redaction / error-sanitization specs (#91, #92, #126) to assert that
  * secret and PII markers never appear even at the most verbose level.
  */
object LogCapture {

  // ScalaTest runs suites in parallel; capture mutates global logback logger levels, so two concurrent captures on the same
  // logger would race on restore. Serialize the whole capture region — bodies are short and few, so this costs nothing.
  private val lock = new AnyRef

  /** Backed by a CopyOnWriteArrayList: the attached logger may be shared with other parallel suites (e.g. `com.zaxxer.hikari`),
    * so appends from their threads must be safe to interleave with our iteration — a plain ArrayList would throw
    * ConcurrentModificationException.
    */
  private final class SafeAppender extends AppenderBase[ILoggingEvent] {
    val events                                      = new CopyOnWriteArrayList[ILoggingEvent]()
    override def append(event: ILoggingEvent): Unit = events.add(event)
  }

  /** Runs `body` with a capturing appender attached to each named logger (forced to `level`), then always detaches and restores
    * the previous level. Returns the formatted messages captured during `body`.
    */
  def capture(loggerNames: Seq[String], level: Level = Level.DEBUG)(body: => Unit): Seq[String] = lock.synchronized {
    val ctx      = LoggerFactory.getILoggerFactory
    val loggers  = loggerNames.map(ctx.getLogger).collect { case l: Logger => l }
    val appender = new SafeAppender()
    appender.start()

    val previous = loggers.map(l => (l, l.getLevel, l.isAdditive))
    loggers.foreach { l =>
      l.addAppender(appender)
      l.setLevel(level)
    }

    try body
    finally {
      // detach and stop BEFORE reading, so no further events can arrive while we render
      previous.foreach { case (l, lvl, additive) =>
        l.detachAppender(appender)
        l.setLevel(lvl)
        l.setAdditive(additive)
      }
      appender.stop()
    }

    import scala.jdk.CollectionConverters._
    appender.events.asScala.toList.map(renderEvent)
  }

  /** Convenience for a single logger. */
  def capture(loggerName: String)(body: => Unit): Seq[String] = capture(Seq(loggerName))(body)

  /** Formatted message plus the full throwable-proxy message chain, so a `logger.debug(msg, throwable)` call is captured with
    * its exception text — an assertion over "does the raw message reach the log" must see the throwable, not just the message.
    */
  private def renderEvent(event: ILoggingEvent): String = {
    val sb = new StringBuilder(event.getFormattedMessage)
    Option(event.getThrowableProxy).foreach(appendProxy(sb, _))
    sb.toString
  }

  private def appendProxy(sb: StringBuilder, tp: ch.qos.logback.classic.spi.IThrowableProxy): Unit = {
    sb.append('\n').append(tp.getClassName)
    Option(tp.getMessage).foreach(m => sb.append(": ").append(m))
    Option(tp.getCause).foreach(appendProxy(sb, _))
    Option(tp.getSuppressed).foreach(_.foreach(appendProxy(sb, _)))
  }
}
