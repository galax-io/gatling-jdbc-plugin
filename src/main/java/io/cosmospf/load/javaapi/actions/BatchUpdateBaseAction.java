package io.cosmospf.load.javaapi.actions;

import io.cosmospf.load.javaapi.internal.Utils;

import java.util.Map;

public class BatchUpdateBaseAction implements BatchAction {
    private final io.cosmospf.load.jdbc.actions.actions.BatchUpdateBaseAction wrapped;
    public BatchUpdateBaseAction(io.cosmospf.load.jdbc.actions.actions.BatchUpdateBaseAction batchUpdateBaseAction) {
        this.wrapped = batchUpdateBaseAction;
    }

    public BatchUpdateValuesStepAction set(Map<String, Object> values){
        return new BatchUpdateValuesStepAction(new io.cosmospf.load.jdbc.actions.actions.BatchUpdateValuesStepAction(wrapped.tableName(),
                Utils.getSeq(values)));
    }
}
