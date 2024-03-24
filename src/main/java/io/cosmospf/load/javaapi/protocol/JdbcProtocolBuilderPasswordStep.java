package io.cosmospf.load.javaapi.protocol;

public class JdbcProtocolBuilderPasswordStep {
    private final io.cosmospf.load.jdbc.protocol.JdbcProtocolBuilderPasswordStep wrapped;

    public JdbcProtocolBuilderPasswordStep(io.cosmospf.load.jdbc.protocol.JdbcProtocolBuilderPasswordStep jdbcProtocolBuilderPasswordStep){
        this.wrapped = jdbcProtocolBuilderPasswordStep;
    }

    public JdbcProtocolBuilderConnectionSettingsStep password(String newValue) {
        return new JdbcProtocolBuilderConnectionSettingsStep(wrapped.password(newValue));
    }
}
