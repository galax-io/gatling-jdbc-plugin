package io.cosmospf.load.jdbc

import io.cosmospf.load.jdbc.actions.actions._
import io.cosmospf.load.jdbc.check.JdbcCheckSupport
import io.cosmospf.load.jdbc.protocol._
import io.gatling.core.protocol.Protocol
import io.gatling.core.session.Expression

trait JdbcDsl extends JdbcCheckSupport {
  def DB: JdbcProtocolBuilderBase.type                                                   = JdbcProtocolBuilderBase
  def jdbc(name: Expression[String]): DBBaseAction                                       = DBBaseAction(name)
  def insertInto(tableName: Expression[String], columns: Columns): BatchInsertBaseAction =
    BatchInsertBaseAction(tableName, columns)
  def update(tableName: Expression[String]): BatchUpdateBaseAction                       = BatchUpdateBaseAction(tableName)

  implicit def configStepToProtocolBuilder(step: JdbcProtocolBuilderConnectionSettingsStep): JdbcProtocolBuilder =
    step.protocolBuilder
  implicit def jdbcProtocolBuilder2jdbcProtocol(builder: JdbcProtocolBuilder): Protocol                          = builder.build
}
