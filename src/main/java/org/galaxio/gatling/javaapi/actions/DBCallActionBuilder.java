package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;

public class DBCallActionBuilder implements ActionBuilder {
    private final org.galaxio.gatling.jdbc.actions.actions.DBCallActionBuilder wrapped;

    public DBCallActionBuilder(org.galaxio.gatling.jdbc.actions.actions.DBCallActionBuilder dbCallActionBuilder){
        this.wrapped = dbCallActionBuilder;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
