package org.galaxio.gatling.javaapi.actions;

import java.util.Map;

import static org.galaxio.gatling.javaapi.internal.Utils.getSeq;

public class QueryActionParamsStep {
    private final org.galaxio.gatling.jdbc.actions.actions.QueryActionParamsStep wrapped;

    public QueryActionParamsStep(org.galaxio.gatling.jdbc.actions.actions.QueryActionParamsStep wrapped){
        this.wrapped = wrapped;
    }

    public QueryActionBuilder params(Map<String, Object> values){
        return new QueryActionBuilder  (wrapped.params(
                getSeq(values)
        ));
    }
}
