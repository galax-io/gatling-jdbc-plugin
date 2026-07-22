package org.galaxio.gatling.jdbc.db.testsupport

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Test-only logback capture: attach an in-memory appender to named loggers, force them to a level, run a block, and return
  * every line the code under test logged. Used by the redaction / error-sanitization specs (#91, #92, #126) to assert that
  * secret and PII markers never appear even at the most verbose level.
  */
object LogCapture {

  /** Runs `body` with a capturing appender attached to each named logger (forced to `level`), then always detaches and restores
    * the previous level. Returns the formatted messages captured during `body`.
    */
  def capture(loggerNames: Seq[String], level: Level = Level.DEBUG)(body: => Unit): Seq[String] = {
    val ctx      = LoggerFactory.getILoggerFactory
    val loggers  = loggerNames.map(ctx.getLogger).collect { case l: Logger => l }
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()

    val previous = loggers.map(l => (l, l.getLevel, l.isAdditive))
    loggers.foreach { l =>
      l.addAppender(appender)
      l.setLevel(level)
    }

    try {
      body
      import scala.jdk.CollectionConverters._
      appender.list.asScala.toList.map(renderEvent)
    } finally {
      previous.foreach { case (l, lvl, additive) =>
        l.detachAppender(appender)
        l.setLevel(lvl)
        l.setAdditive(additive)
      }
      appender.stop()
    }
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
