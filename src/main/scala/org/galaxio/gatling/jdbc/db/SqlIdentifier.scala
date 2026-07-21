package org.galaxio.gatling.jdbc.db

/** Thrown when a table/column identifier fails [[SqlIdentifier.validate]] (#124). */
final class InvalidSqlIdentifierException(val value: String) extends IllegalArgumentException(
      s"Invalid SQL identifier '$value'. Accepted forms: unquoted [A-Za-z_][A-Za-z0-9_$$]{0,127}, " +
        "ANSI-quoted \"...\" (escape \" by doubling) or backtick-quoted `...` (escape ` by doubling), " +
        "joined by '.' into at most 3 segments; '{', '}' and NUL are never allowed.",
    )

/** Allowlist validation for SQL identifiers entering statement text (#124, spec 003 identifier-grammar contract).
  *
  * Applied to session-resolved table names (the feeder-reachable input) and to static column names before any SQL is assembled.
  * `{` and `}` are rejected even inside quoted segments: insert/update SQL derives `{column}` parameter placeholders from
  * column names, and a brace inside an identifier would terminate the interpolator's placeholder early.
  */
object SqlIdentifier {

  private val MaxSegments       = 3
  private val UnquotedPattern   = "[A-Za-z_][A-Za-z0-9_$]{0,127}".r
  private val ForbiddenAnywhere = Set('\u0000', '{', '}')

  def validate(value: String): Either[InvalidSqlIdentifierException, String] =
    if (isValid(value)) Right(value) else Left(new InvalidSqlIdentifierException(value))

  def isValid(value: String): Boolean =
    value != null && value.nonEmpty && !value.exists(ForbiddenAnywhere) && {
      splitSegments(value) match {
        case Some(segments) => segments.nonEmpty && segments.sizeIs <= MaxSegments && segments.forall(validSegment)
        case None           => false
      }
    }

  /** Splits on `.` while treating dots inside quoted segments as data; None on structurally broken input (unbalanced quotes,
    * leading/trailing/double dots, trailing garbage after a closing quote).
    */
  private def splitSegments(value: String): Option[List[String]] = {
    val segments = List.newBuilder[String]
    val length   = value.length
    var i        = 0
    while (i < length) {
      val start = i
      val first = value.charAt(i)
      if (first == '"' || first == '`') {
        i += 1
        var closed = false
        while (i < length && !closed) {
          if (value.charAt(i) == first) {
            if (i + 1 < length && value.charAt(i + 1) == first) i += 2 // doubled quote — escaped, keep scanning
            else { closed = true; i += 1 }
          } else i += 1
        }
        if (!closed) return None
      } else {
        while (i < length && value.charAt(i) != '.') i += 1
      }
      if (i == start) return None // empty segment (leading dot or "..")
      segments += value.substring(start, i)
      if (i < length) {
        if (value.charAt(i) != '.') return None // garbage after a closing quote
        i += 1
        if (i == length) return None            // trailing dot
      }
    }
    Some(segments.result())
  }

  private def validSegment(segment: String): Boolean =
    segment.head match {
      case '"' => validQuoted(segment, '"')
      case '`' => validQuoted(segment, '`')
      case _   => UnquotedPattern.matches(segment)
    }

  private def validQuoted(segment: String, quote: Char): Boolean = {
    if (segment.length < 3 || segment.last != quote) return false
    val body = segment.substring(1, segment.length - 1)
    // doubling is the only escape; after removing doubled quotes no lone quote may remain
    !body.replace(s"$quote$quote", "").contains(quote)
  }
}
