package org.galaxio.gatling.javaapi.protocol;

import com.zaxxer.hikari.HikariConfig;

import javax.annotation.Nonnull;

public class JdbcProtocolBuilderBase {
    public JdbcProtocolBuilderBase() {}

    public JdbcProtocolBuilderUsernameStep url(@Nonnull String url) {
        return new JdbcProtocolBuilderUsernameStep(org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderBase.url(url));
    }

    public JdbcProtocolBuilder hikariConfig(HikariConfig cfg) {
        return new JdbcProtocolBuilder(org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderBase.hikariConfig(cfg));
    }
}
