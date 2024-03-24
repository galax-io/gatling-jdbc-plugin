package io.cosmospf.load.javaapi.actions;

import java.util.Map;

import static io.cosmospf.load.javaapi.internal.Utils.getSeq;

public class QueryActionParamsStep {
    private final io.cosmospf.load.jdbc.actions.actions.QueryActionParamsStep wrapped;

    public QueryActionParamsStep(io.cosmospf.load.jdbc.actions.actions.QueryActionParamsStep wrapped){
        this.wrapped = wrapped;
    }

    public QueryActionBuilder params(Map<String, Object> values){
        return new QueryActionBuilder  (wrapped.params(
                getSeq(values)
        ));
    }
}
