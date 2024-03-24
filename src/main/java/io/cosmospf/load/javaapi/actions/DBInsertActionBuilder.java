package io.cosmospf.load.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;

public class DBInsertActionBuilder implements ActionBuilder {
    private final io.cosmospf.load.jdbc.actions.actions.DBInsertActionBuilder wrapped;

    public DBInsertActionBuilder(io.cosmospf.load.jdbc.actions.actions.DBInsertActionBuilder dbInsertActionBuilder){
        this.wrapped = dbInsertActionBuilder;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
