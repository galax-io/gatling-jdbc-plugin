package io.cosmospf.load.jdbc.test

import io.cosmospf.load.javaapi.protocol.JdbcProtocolBuilder

import  io.cosmospf.load.javaapi.JdbcDsl.DB

object KtJdbcProtocol {
    var dataBase: JdbcProtocolBuilder = DB()
            .url("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .maximumPoolSize(23)
            .protocolBuilder()
}
