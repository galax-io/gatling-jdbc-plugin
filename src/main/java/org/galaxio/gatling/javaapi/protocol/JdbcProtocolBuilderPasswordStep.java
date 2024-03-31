package org.galaxio.gatling.javaapi.protocol;

public class JdbcProtocolBuilderPasswordStep {
    private final org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderPasswordStep wrapped;

    public JdbcProtocolBuilderPasswordStep(org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderPasswordStep jdbcProtocolBuilderPasswordStep){
        this.wrapped = jdbcProtocolBuilderPasswordStep;
    }

    public JdbcProtocolBuilderConnectionSettingsStep password(String newValue) {
        return new JdbcProtocolBuilderConnectionSettingsStep(wrapped.password(newValue));
    }
}
