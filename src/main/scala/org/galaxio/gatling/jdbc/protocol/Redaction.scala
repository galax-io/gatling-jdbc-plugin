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
}
