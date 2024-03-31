package org.galaxio.gatling.jdbc.test

import org.galaxio.gatling.javaapi.protocol.JdbcProtocolBuilder

import  org.galaxio.gatling.javaapi.JdbcDsl.DB

object KtJdbcProtocol {
    var dataBase: JdbcProtocolBuilder = DB()
            .url("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .maximumPoolSize(23)
            .protocolBuilder()
}
