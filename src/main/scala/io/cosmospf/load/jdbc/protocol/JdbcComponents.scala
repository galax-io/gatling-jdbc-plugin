package io.cosmospf.load.jdbc.protocol

import io.gatling.core.protocol.ProtocolComponents
import io.gatling.core.session.Session
import io.cosmospf.load.jdbc.db.JDBCClient

case class JdbcComponents(client: JDBCClient) extends ProtocolComponents {
  override def onStart: Session => Session = Session.Identity

  override def onExit: Session => Unit = _ => ()
}
