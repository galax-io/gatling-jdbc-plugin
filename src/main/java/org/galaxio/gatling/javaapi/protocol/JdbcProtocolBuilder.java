package org.galaxio.gatling.javaapi.protocol;

import io.gatling.javaapi.core.ProtocolBuilder;

public class JdbcProtocolBuilder implements ProtocolBuilder {
    private final org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilder wrapped;

    public JdbcProtocolBuilder(org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilder jdbcProtocolBuilder){
        this.wrapped = jdbcProtocolBuilder;
    }
    @Override
    public io.gatling.core.protocol.Protocol protocol() {
        return wrapped.build();
    }
}
