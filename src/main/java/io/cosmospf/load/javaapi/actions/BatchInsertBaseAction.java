package io.cosmospf.load.javaapi.actions;

import java.util.Map;

public class BatchInsertBaseAction implements BatchAction {
    private final io.cosmospf.load.jdbc.actions.actions.BatchInsertBaseAction wrapped;

    public BatchInsertBaseAction(io.cosmospf.load.jdbc.actions.actions.BatchInsertBaseAction batchInsertBaseAction){
        this.wrapped = batchInsertBaseAction;
    }

    public BatchInsertAction values(Map<String, Object> ps){
        return new BatchInsertAction(BatchInsertAction.toScala(wrapped.tableName(), ps, wrapped.columns()));
    }

}
