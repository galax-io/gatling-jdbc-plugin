package org.galaxio.gatling.javaapi.actions;

public class BatchParameterizedUpdateAction implements BatchAction {
    public org.galaxio.gatling.jdbc.actions.actions.BatchParameterizedUpdateAction wrapped;

    public BatchParameterizedUpdateAction(org.galaxio.gatling.jdbc.actions.actions.BatchParameterizedUpdateAction batchParameterizedUpdateAction) {
        this.wrapped = batchParameterizedUpdateAction;
    }
}
