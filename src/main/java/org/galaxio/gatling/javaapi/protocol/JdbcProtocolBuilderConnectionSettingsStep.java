package org.galaxio.gatling.javaapi.protocol;

import scala.jdk.javaapi.DurationConverters;

import java.time.Duration;

public class JdbcProtocolBuilderConnectionSettingsStep {
    private final org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderConnectionSettingsStep wrapped;

    public JdbcProtocolBuilderConnectionSettingsStep(org.galaxio.gatling.jdbc.protocol.JdbcProtocolBuilderConnectionSettingsStep jdbcProtocolBuilderConnectionSettingsStep){
        this.wrapped = jdbcProtocolBuilderConnectionSettingsStep;
    }

    public JdbcProtocolBuilder protocolBuilder(){
        return new JdbcProtocolBuilder(wrapped.protocolBuilder());
    }

    public JdbcProtocolBuilderConnectionSettingsStep maximumPoolSize(Integer newValue){
        return new JdbcProtocolBuilderConnectionSettingsStep(wrapped.maximumPoolSize(newValue));
    }

    public JdbcProtocolBuilderConnectionSettingsStep minimumIdleConnections(Integer newValue){
        return new JdbcProtocolBuilderConnectionSettingsStep(wrapped.minimumIdleConnections(newValue));
    }

    public JdbcProtocolBuilderConnectionSettingsStep connectionTimeout(Duration newValue){
        return new JdbcProtocolBuilderConnectionSettingsStep(wrapped.connectionTimeout(DurationConverters.toScala(newValue)));
    }
}
