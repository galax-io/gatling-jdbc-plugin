package org.galaxio.performance.jdbc.test;

import org.galaxio.gatling.javaapi.protocol.JdbcProtocolBuilder;

import static org.galaxio.gatling.javaapi.JdbcDsl.DB;

public class JdbcProtocol {
    public static JdbcProtocolBuilder dataBase = DB()
            .url("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .maximumPoolSize(23)
            .protocolBuilder();
}
