package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;

public class TransactionActionBuilder implements ActionBuilder {
    private final org.galaxio.gatling.jdbc.actions.actions.TransactionActionBuilder wrapped;

    public TransactionActionBuilder(org.galaxio.gatling.jdbc.actions.actions.TransactionActionBuilder wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
