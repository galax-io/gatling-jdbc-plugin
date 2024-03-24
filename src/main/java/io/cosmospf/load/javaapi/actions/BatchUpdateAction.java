package io.cosmospf.load.javaapi.actions;

public class BatchUpdateAction implements BatchAction {
    public io.cosmospf.load.jdbc.actions.actions.BatchUpdateAction wrapped;
    public BatchUpdateAction(io.cosmospf.load.jdbc.actions.actions.BatchUpdateAction batchUpdateAction){
        this.wrapped = batchUpdateAction;
    }
}
