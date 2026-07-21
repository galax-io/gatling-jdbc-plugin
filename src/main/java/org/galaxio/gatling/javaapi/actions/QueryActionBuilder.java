package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;
import java.util.Arrays;
import java.util.List;

public class QueryActionBuilder implements ActionBuilder {
    private final org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder wrapped;

    public QueryActionBuilder(org.galaxio.gatling.jdbc.actions.actions.QueryActionBuilder wrapped){
        this.wrapped = wrapped;
    }

    public QueryActionBuilder check(Object...checks){
        return check(Arrays.asList(checks));
    }

    /** Returns a new builder; the receiver (and any other branch derived from it) stays unmodified (#80). */
    public QueryActionBuilder check(List<Object> checks) {
        return new QueryActionBuilder(wrapped.check(org.galaxio.gatling.jdbc.internal.JdbcCheck.toScalaChecks(checks)));
    }

    /** Caps the rows read for this query (#86); a result exceeding the cap fails the request instead of truncating. */
    public QueryActionBuilder maxRows(int n) {
        return new QueryActionBuilder(wrapped.maxRows(n));
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
