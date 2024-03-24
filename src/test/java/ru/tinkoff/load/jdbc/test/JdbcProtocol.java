package io.cosmospf.load.jdbc.test;

import io.cosmospf.load.javaapi.protocol.JdbcProtocolBuilder;

import static io.cosmospf.load.javaapi.JdbcDsl.DB;

public class JdbcProtocol {
    public static JdbcProtocolBuilder dataBase = DB()
            .url("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .maximumPoolSize(23)
            .protocolBuilder();
}
