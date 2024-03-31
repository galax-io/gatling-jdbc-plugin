package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;
public class BatchActionBuilder implements ActionBuilder{
    private final org.galaxio.gatling.jdbc.actions.actions.BatchActionBuilder wrapped;

    public BatchActionBuilder(org.galaxio.gatling.jdbc.actions.actions.BatchActionBuilder batchActionBuilder){
        this.wrapped = batchActionBuilder;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
