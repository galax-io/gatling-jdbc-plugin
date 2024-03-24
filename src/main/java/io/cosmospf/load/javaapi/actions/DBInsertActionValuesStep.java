package io.cosmospf.load.javaapi.actions;

import io.cosmospf.load.javaapi.internal.Utils;

import java.util.Map;

public class DBInsertActionValuesStep {
    private final io.cosmospf.load.jdbc.actions.actions.DBInsertActionValuesStep wrapped;

    public DBInsertActionValuesStep(io.cosmospf.load.jdbc.actions.actions.DBInsertActionValuesStep wrapped){
        this.wrapped = wrapped;
    }

    public DBInsertActionBuilder values(Map<String, Object> vals){
        return new DBInsertActionBuilder(wrapped.values(
                Utils.getSeq(vals)
        ));
    }
}
