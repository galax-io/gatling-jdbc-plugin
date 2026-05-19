package org.galaxio.gatling.jdbc.protocol

import io.gatling.core.protocol.ProtocolComponents
import io.gatling.core.session.Session
import org.galaxio.gatling.jdbc.db.JDBCClient

case class JdbcComponents(client: JDBCClient) extends ProtocolComponents {
  override def onStart: Session => Session = Session.Identity

  override def onExit: Session => Unit = _ => ()
}
