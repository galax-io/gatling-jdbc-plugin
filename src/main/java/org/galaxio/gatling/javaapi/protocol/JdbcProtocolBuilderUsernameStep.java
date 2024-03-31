package org.galaxio.gatling.javaapi.protocol;

public class JdbcProtocolBuilderUsernameStep {
    private final org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderUsernameStep wrapped;

    public JdbcProtocolBuilderUsernameStep(org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderUsernameStep jdbcProtocolBuilderUsernameStep){
        this.wrapped = jdbcProtocolBuilderUsernameStep;
    }

    public JdbcProtocolBuilderPasswordStep username(String newValue) {
        return new JdbcProtocolBuilderPasswordStep(wrapped.username(newValue));
    }
}
