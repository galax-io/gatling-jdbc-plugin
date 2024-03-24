package io.cosmospf.load.javaapi.actions;

import static io.gatling.javaapi.core.internal.Expressions.toStringExpression;

public class BatchUpdateValuesStepAction {
    private final io.cosmospf.load.jdbc.actions.actions.BatchUpdateValuesStepAction wrapped;

    public BatchUpdateValuesStepAction(io.cosmospf.load.jdbc.actions.actions.BatchUpdateValuesStepAction batchUpdateValuesStepAction) {
        this.wrapped = batchUpdateValuesStepAction;
    }
    public BatchUpdateAction where(String whereExpression){
        return new BatchUpdateAction(new io.cosmospf.load.jdbc.actions.actions.BatchUpdateAction(wrapped.tableName(),
                wrapped.updateValues(),
                scala.Option.apply(toStringExpression(whereExpression))));

    }
}
