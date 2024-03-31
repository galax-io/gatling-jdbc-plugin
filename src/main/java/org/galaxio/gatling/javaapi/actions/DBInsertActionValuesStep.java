package org.galaxio.gatling.javaapi.actions;

import org.galaxio.gatling.javaapi.internal.Utils;

import java.util.Map;

public class DBInsertActionValuesStep {
    private final org.galaxio.gatling.jdbc.actions.actions.DBInsertActionValuesStep wrapped;

    public DBInsertActionValuesStep(org.galaxio.gatling.jdbc.actions.actions.DBInsertActionValuesStep wrapped){
        this.wrapped = wrapped;
    }

    public DBInsertActionBuilder values(Map<String, Object> vals){
        return new DBInsertActionBuilder(wrapped.values(
                Utils.getSeq(vals)
        ));
    }
}
