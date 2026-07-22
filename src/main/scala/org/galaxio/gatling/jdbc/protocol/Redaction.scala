package org.galaxio.gatling.jdbc.protocol

/** Central redaction for anything the plugin itself renders (#91, #92, #126). The only place that turns sensitive material into
  * printable text — kept `private[jdbc]` so it is not part of the published API.
  */
private[jdbc] object Redaction {

  final val Mask = "*****"

  /** Case-insensitive substring patterns marking a property name as secret-bearing (#92). Separators are stripped first, so
    * `api-key`/`api_key` match `apikey`. Names outside this list are the user's responsibility (documented boundary).
    */
  private val SecretNamePatterns = Seq("password", "secret", "token", "passphrase", "credential", "apikey")

  def isSecretProperty(name: String): Boolean = {
    if (name == null) false
    else {
      val normalized = name.toLowerCase.replace("-", "").replace("_", "")
      SecretNamePatterns.exists(normalized.contains)
    }
  }

  /** Redacts `user:pass@` credentials embedded in a connection URL. URLs without credentials pass through unchanged; anything
    * that does not parse as a credential-bearing authority is returned verbatim (never throws — this runs on logging paths).
    */
  def redactUrl(url: String): String = {
    if (url == null) "<null url>"
    else {
      // match "//user:password@" (the credential portion of an authority) and mask only the password
      val CredsInAuthority = "(//[^/:@\\s]+):([^/@\\s]+)@".r
      CredsInAuthority.replaceAllIn(url, m => java.util.regex.Matcher.quoteReplacement(s"${m.group(1)}:$Mask@"))
    }
  }

  private final val MaxSuppressedShown = 3
  private final val MaxMessageLength   = 512

  /** Builds the value-free message recorded into Gatling stats/reports for a failed execution (#126). Rebuilt from structured
    * fields (class, SQLState, vendor code) rather than the raw driver text — which echoes feeder values — so no user data can
    * leak into a shared artifact. The full raw throwable belongs on the DEBUG log instead. Total length is hard-bounded; a
    * truncated message ends with an ellipsis so the cut is visible.
    */
  def koMessage(t: Throwable): String = {
    val suppressed = t.getSuppressed
    val primary    = describe(t)
    val full       =
      if (suppressed.isEmpty) primary
      else {
        val shown = suppressed.take(MaxSuppressedShown).map(describe).mkString("; ")
        val more  = if (suppressed.length > MaxSuppressedShown) s" (+${suppressed.length - MaxSuppressedShown} more)" else ""
        s"$primary [cleanup also failed: $shown$more]"
      }
    bound(full)
  }

  /** A single throwable rendered value-free: SQL errors carry only their structured fields; everything else is reduced to its
    * class name (a plugin exception message may itself embed the rejected value, so it is never reproduced here).
    */
  private def describe(t: Throwable): String = t match {
    case safe: org.galaxio.gatling.jdbc.db.SafeDiagnosticMessage =>
      // plugin-authored, data-free by construction (#126) — its message is safe to surface
      s"${t.getClass.getName}: ${safe.getMessage}"
    case sql: java.sql.SQLException                              =>
      s"${t.getClass.getName} [SQLState=${Option(sql.getSQLState).getOrElse("")}, code=${sql.getErrorCode}]"
    case _                                                       => t.getClass.getName
  }

  private def bound(s: String): String =
    if (s.length <= MaxMessageLength) s else s.take(MaxMessageLength - 1) + "…"
}
