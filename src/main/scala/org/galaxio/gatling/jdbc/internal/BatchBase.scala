package org.galaxio.gatling.jdbc.internal

import org.galaxio.gatling.javaapi.actions.{BatchInsertAction, BatchParameterizedUpdateAction, BatchUpdateAction}
import org.galaxio.gatling.jdbc.actions.actions.BatchAction

object BatchBase {

  def toScalaBatch(batchAction: Object): BatchAction = {
    batchAction match {
      case insert: BatchInsertAction              => insert.wrapped
      case update: BatchUpdateAction              => update.wrapped
      case update: BatchParameterizedUpdateAction => update.wrapped
      case unknown                                => throw new IllegalArgumentException(s"JDBC DSL doesn't support $unknown")
    }
  }
}
