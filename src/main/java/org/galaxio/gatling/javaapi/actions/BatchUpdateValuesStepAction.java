package org.galaxio.gatling.javaapi.actions;

import static io.gatling.javaapi.core.internal.Expressions.toStringExpression;

public class BatchUpdateValuesStepAction {
    private final org.galaxio.gatling.jdbc.actions.actions.BatchUpdateValuesStepAction wrapped;

    public BatchUpdateValuesStepAction(org.galaxio.gatling.jdbc.actions.actions.BatchUpdateValuesStepAction batchUpdateValuesStepAction) {
        this.wrapped = batchUpdateValuesStepAction;
    }
    public BatchUpdateAction where(String whereExpression){
        return new BatchUpdateAction(new org.galaxio.gatling.jdbc.actions.actions.BatchUpdateAction(wrapped.tableName(),
                wrapped.updateValues(),
                scala.Option.apply(toStringExpression(whereExpression))));

    }
}
