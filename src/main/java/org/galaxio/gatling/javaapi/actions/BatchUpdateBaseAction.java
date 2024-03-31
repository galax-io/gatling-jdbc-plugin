package org.galaxio.gatling.javaapi.actions;

import org.galaxio.gatling.javaapi.internal.Utils;

import java.util.Map;

public class BatchUpdateBaseAction implements BatchAction {
    private final org.galaxio.gatling.jdbc.actions.actions.BatchUpdateBaseAction wrapped;
    public BatchUpdateBaseAction(org.galaxio.gatling.jdbc.actions.actions.BatchUpdateBaseAction batchUpdateBaseAction) {
        this.wrapped = batchUpdateBaseAction;
    }

    public BatchUpdateValuesStepAction set(Map<String, Object> values){
        return new BatchUpdateValuesStepAction(new org.galaxio.gatling.jdbc.actions.actions.BatchUpdateValuesStepAction(wrapped.tableName(),
                Utils.getSeq(values)));
    }
}
