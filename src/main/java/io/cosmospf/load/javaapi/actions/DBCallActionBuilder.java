package io.cosmospf.load.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;

public class DBCallActionBuilder implements ActionBuilder {
    private final io.cosmospf.load.jdbc.actions.actions.DBCallActionBuilder wrapped;

    public DBCallActionBuilder(io.cosmospf.load.jdbc.actions.actions.DBCallActionBuilder dbCallActionBuilder){
        this.wrapped = dbCallActionBuilder;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
