package org.galaxio.gatling.javaapi.actions;

import org.galaxio.gatling.javaapi.internal.Utils;

import java.util.Map;

public class DBCallActionParamStep {
    private final org.galaxio.gatling.jdbc.actions.actions.DBCallActionParamsStep wrapped;

    public DBCallActionParamStep(org.galaxio.gatling.jdbc.actions.actions.DBCallActionParamsStep wrapped){
        this.wrapped = wrapped;
    }

    public DBCallActionBuilder params(Map<String, Object> values){
        return new DBCallActionBuilder(wrapped.params(
                Utils.getSeq(values))
        );
    }
}
