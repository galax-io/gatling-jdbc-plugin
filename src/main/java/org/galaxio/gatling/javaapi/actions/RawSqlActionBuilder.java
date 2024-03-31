package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;

public class RawSqlActionBuilder implements ActionBuilder{
    private final org.galaxio.gatling.jdbc.actions.actions.RawSqlActionBuilder wrapped;

    public RawSqlActionBuilder(org.galaxio.gatling.jdbc.actions.actions.RawSqlActionBuilder wrapped){
        this.wrapped = wrapped;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
