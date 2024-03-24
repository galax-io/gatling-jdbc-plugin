package io.cosmospf.load.javaapi.protocol;

public class JdbcProtocolBuilderUsernameStep {
    private final io.cosmospf.load.jdbc.protocol.JdbcProtocolBuilderUsernameStep wrapped;

    public JdbcProtocolBuilderUsernameStep(io.cosmospf.load.jdbc.protocol.JdbcProtocolBuilderUsernameStep jdbcProtocolBuilderUsernameStep){
        this.wrapped = jdbcProtocolBuilderUsernameStep;
    }

    public JdbcProtocolBuilderPasswordStep username(String newValue) {
        return new JdbcProtocolBuilderPasswordStep(wrapped.username(newValue));
    }
}
