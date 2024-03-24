package io.cosmospf.load.jdbc.internal

import io.cosmospf.load.jdbc.actions.actions.BatchAction

object BatchBase {

  def toScalaBatch(batchAction: Object): BatchAction = {
    batchAction match {
      case insert: io.cosmospf.load.javaapi.actions.BatchInsertAction => insert.wrapped
      case update: io.cosmospf.load.javaapi.actions.BatchUpdateAction => update.wrapped
      case unknown                                                    => throw new IllegalArgumentException(s"JDBC DSL doesn't support $unknown")
    }
  }
}
