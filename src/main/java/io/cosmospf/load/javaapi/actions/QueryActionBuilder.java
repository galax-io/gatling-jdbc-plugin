package io.cosmospf.load.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;
import java.util.Arrays;
import java.util.List;

public class QueryActionBuilder implements ActionBuilder {
    private io.cosmospf.load.jdbc.actions.actions.QueryActionBuilder wrapped;

    public QueryActionBuilder(io.cosmospf.load.jdbc.actions.actions.QueryActionBuilder wrapped){
        this.wrapped = wrapped;
    }

    public QueryActionBuilder check(Object...checks){
        return check(Arrays.asList(checks));
    }

    public QueryActionBuilder check(List<Object> checks) {
        this.wrapped = wrapped.check(io.cosmospf.load.jdbc.internal.JdbcCheck.toScalaChecks(checks));
        return this;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
