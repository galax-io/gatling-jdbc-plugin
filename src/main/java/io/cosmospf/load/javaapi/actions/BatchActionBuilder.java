package io.cosmospf.load.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;
public class BatchActionBuilder implements ActionBuilder{
    private final io.cosmospf.load.jdbc.actions.actions.BatchActionBuilder wrapped;

    public BatchActionBuilder(io.cosmospf.load.jdbc.actions.actions.BatchActionBuilder batchActionBuilder){
        this.wrapped = batchActionBuilder;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
