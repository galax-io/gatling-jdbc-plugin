package org.galaxio.gatling.jdbc.db

/** Thrown when a query's result contains the same column label more than once (#123).
  *
  * A duplicate label would silently overwrite a value in the session row map; the operation fails instead, before the first row
  * is mapped. Workaround: alias each column uniquely (`SELECT col AS unique_name`).
  */
final class DuplicateColumnLabelException(val duplicatedLabels: Seq[String]) extends IllegalStateException(
      s"Duplicate ResultSet column label(s): ${duplicatedLabels.mkString(", ")}. " +
        "A duplicate label would silently overwrite a value; alias each column uniquely (SELECT col AS unique_name).",
    )
