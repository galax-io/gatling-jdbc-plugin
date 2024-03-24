package io.cosmospf.load.javaapi.protocol;

import com.zaxxer.hikari.HikariConfig;

import javax.annotation.Nonnull;

public class JdbcProtocolBuilderBase {
    public JdbcProtocolBuilderBase() {}

    public JdbcProtocolBuilderUsernameStep url(@Nonnull String url) {
        return new JdbcProtocolBuilderUsernameStep(io.cosmospf.load.jdbc.protocol.JdbcProtocolBuilderBase.url(url));
    }

    public JdbcProtocolBuilder hikariConfig(HikariConfig cfg) {
        return new JdbcProtocolBuilder(io.cosmospf.load.jdbc.protocol.JdbcProtocolBuilderBase.hikariConfig(cfg));
    }
}
