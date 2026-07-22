package org.galaxio.gatling.jdbc.db

/** Marker for plugin-authored exceptions whose message is data-free by construction (#126): the KO report may reproduce their
  * message text, unlike driver messages (which echo feeder values) or identifier-rejection messages (which embed the rejected
  * input). Mix it in only when the message provably contains no user data.
  */
private[jdbc] trait SafeDiagnosticMessage { self: Throwable => }

/** Thrown when a query result exceeds the configured `maxRows` cap (#86). The message names only the cap, so it is safe to
  * surface in the KO report (carries [[SafeDiagnosticMessage]]).
  */
private[jdbc] final class MaxRowsExceededException(cap: Int)
    extends IllegalStateException(s"Query result exceeded the configured maxRows cap of $cap; failing instead of truncating")
    with SafeDiagnosticMessage

/** Thrown when a query's result contains the same column label more than once (#123).
  *
  * A duplicate label would silently overwrite a value in the session row map; the operation fails instead, before the first row
  * is mapped. Workaround: alias each column uniquely (`SELECT col AS unique_name`).
  */
final class DuplicateColumnLabelException(val duplicatedLabels: Seq[String]) extends IllegalStateException(
      s"Duplicate ResultSet column label(s): ${duplicatedLabels.mkString(", ")}. " +
        "A duplicate label would silently overwrite a value; alias each column uniquely (SELECT col AS unique_name).",
    )
