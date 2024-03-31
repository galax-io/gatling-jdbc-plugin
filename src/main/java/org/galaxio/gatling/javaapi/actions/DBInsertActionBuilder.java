package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;

public class DBInsertActionBuilder implements ActionBuilder {
    private final org.galaxio.gatling.jdbc.actions.actions.DBInsertActionBuilder wrapped;

    public DBInsertActionBuilder(org.galaxio.gatling.jdbc.actions.actions.DBInsertActionBuilder dbInsertActionBuilder){
        this.wrapped = dbInsertActionBuilder;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
