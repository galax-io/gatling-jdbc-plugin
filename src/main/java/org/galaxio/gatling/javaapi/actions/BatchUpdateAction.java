package org.galaxio.gatling.javaapi.actions;

public class BatchUpdateAction implements BatchAction {
    public org.galaxio.gatling.jdbc.actions.actions.BatchUpdateAction wrapped;
    public BatchUpdateAction(org.galaxio.gatling.jdbc.actions.actions.BatchUpdateAction batchUpdateAction){
        this.wrapped = batchUpdateAction;
    }
}
