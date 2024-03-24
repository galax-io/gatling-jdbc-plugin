package io.cosmospf.load.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;

public class RawSqlActionBuilder implements ActionBuilder{
    private final io.cosmospf.load.jdbc.actions.actions.RawSqlActionBuilder wrapped;

    public RawSqlActionBuilder(io.cosmospf.load.jdbc.actions.actions.RawSqlActionBuilder wrapped){
        this.wrapped = wrapped;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
