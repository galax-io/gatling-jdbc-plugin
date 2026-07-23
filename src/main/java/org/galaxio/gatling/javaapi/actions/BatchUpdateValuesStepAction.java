package org.galaxio.gatling.javaapi.actions;

import org.galaxio.gatling.javaapi.internal.Utils;

import java.util.Map;

public class BatchUpdateValuesStepAction {
    private final org.galaxio.gatling.jdbc.actions.actions.BatchUpdateValuesStepAction wrapped;

    public BatchUpdateValuesStepAction(org.galaxio.gatling.jdbc.actions.actions.BatchUpdateValuesStepAction batchUpdateValuesStepAction) {
        this.wrapped = batchUpdateValuesStepAction;
    }

    /**
     * Static, author-fixed WHERE clause. Gatling EL ({@code #{...}}) inside the clause is rejected at
     * construction time — it would resolve session data into statement text (issue #125); bind dynamic
     * values with {@code where(String, Map)} instead.
     */
    public BatchUpdateAction where(String whereExpression){
        return new BatchUpdateAction(wrapped.where(whereExpression));
    }

    /**
     * Static WHERE clause with dynamic values bound as data (issue #125): {@code {name}} placeholders in
     * the clause are bound from {@code params} through the prepared-statement machinery — a value can only
     * match rows, never change the predicate.
     *
     * <p>Example:
     * <pre>{@code
     * update("users").set(Map.of("STATUS", "BLOCKED")).where("EMAIL = {email}", Map.of("email", "#{userEmail}"))
     * }</pre>
     */
    public BatchParameterizedUpdateAction where(String whereClause, Map<String, Object> params){
        return new BatchParameterizedUpdateAction(wrapped.where(whereClause, Utils.getSeq(params)));
    }
}
