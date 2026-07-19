package org.galaxio.gatling.jdbc.db.testsupport

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

/** Single source of the in-memory H2 pool settings every spec uses.
  *
  * `DB_CLOSE_DELAY=-1` keeps the named database alive for the whole JVM, so repeated `testOnly` runs reuse it — specs are
  * expected to create tables with IF NOT EXISTS and clear rows themselves.
  */
object H2 {

  def jdbcUrl(dbName: String): String = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

  def config(dbName: String, poolSize: Int): HikariConfig = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl(jdbcUrl(dbName))
    cfg.setUsername("sa")
    cfg.setPassword("")
    cfg.setMaximumPoolSize(poolSize)
    cfg
  }

  def dataSource(dbName: String, poolSize: Int): HikariDataSource =
    new HikariDataSource(config(dbName, poolSize))
}
