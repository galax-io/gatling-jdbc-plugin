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
      // Locale.ROOT: a locale-sensitive lowercase (e.g. Turkish) would turn APIKEY's 'I' into dotless 'ı' and miss the pattern
      val normalized = name.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "")
      SecretNamePatterns.exists(normalized.contains)
    }
  }

  private def quote(s: String): String = java.util.regex.Matcher.quoteReplacement(s)

  // (a) RFC authority userinfo: "//user:password@host". The password runs to the LAST '@' before the authority terminator
  //     ('/', '?', ';', or end), so passwords containing '@' are still fully masked; '/','?',';' cannot appear unencoded in it.
  private val AuthorityCreds = "(//[^/:@\\s]+):[^/?;\\s]*@".r
  // (b) key=value credentials in query strings or JDBC property lists: "?password=", "&password=", ";password=".
  private val UrlParam       = "([?&;])([^=&;\\s]+)=([^&;\\s]*)".r
  // (c) Oracle thin form: "…:user/password@host".
  private val OracleCreds    = "([:/][A-Za-z0-9_.]+)/[^/@\\s]+@".r

  /** Redacts credentials embedded in a connection URL across the forms JDBC drivers actually use — authority `user:pass@`
    * (incl. passwords with special characters), `key=value` query/property credentials (`?password=`, `;password=`), and the
    * Oracle thin `user/pass@` form. URLs without credentials pass through unchanged; never throws — this runs on logging paths.
    */
  def redactUrl(url: String): String = {
    if (url == null) "<null url>"
    else {
      var out = url
      out = AuthorityCreds.replaceAllIn(out, m => quote(s"${m.group(1)}:$Mask@"))
      out = UrlParam.replaceAllIn(
        out,
        m => quote(if (isSecretProperty(m.group(2))) s"${m.group(1)}${m.group(2)}=$Mask" else m.matched),
      )
      out = OracleCreds.replaceAllIn(out, m => quote(s"${m.group(1)}/$Mask@"))
      out
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
    case _                                                       =>
      // a driver/pool may wrap the SQLException as a cause — unwrap one level to keep the structured detail, value-free
      t.getCause match {
        case null                => t.getClass.getName
        case cause if cause eq t => t.getClass.getName
        case cause               => s"${t.getClass.getName} caused by ${describe(cause)}"
      }
  }

  private def bound(s: String): String =
    if (s.length <= MaxMessageLength) s else s.take(MaxMessageLength - 1) + "…"
}
