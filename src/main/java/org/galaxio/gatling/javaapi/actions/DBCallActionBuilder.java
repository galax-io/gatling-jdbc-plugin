package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.ActionBuilder;
import scala.collection.JavaConverters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DBCallActionBuilder implements ActionBuilder {
    private final org.galaxio.gatling.jdbc.actions.actions.DBCallActionBuilder wrapped;

    public DBCallActionBuilder(org.galaxio.gatling.jdbc.actions.actions.DBCallActionBuilder dbCallActionBuilder){
        this.wrapped = dbCallActionBuilder;
    }

    /**
     * Declare OUT parameters for the stored procedure call.
     *
     * <p>Each entry maps a parameter name (matching the placeholder in the CALL statement, e.g.
     * {@code {outResult}}) to its SQL type constant from {@link java.sql.Types}.
     *
     * <p>After execution the value returned by the database for each OUT parameter is stored in the
     * Gatling session under the same parameter name, making it available to downstream actions and
     * checks via Gatling EL (e.g. {@code "#{outResult}"}).
     *
     * <p>Example:
     * <pre>{@code
     * jdbc("call my_proc")
     *   .call("MY_PROC")
     *   .params(Map.of("inVal", 42))
     *   .outParams(Map.of("outResult", java.sql.Types.INTEGER))
     * }</pre>
     *
     * @param params map of parameter name to {@link java.sql.Types} constant
     * @return a new builder with OUT parameters configured
     */
    @SuppressWarnings("deprecation")
    public DBCallActionBuilder outParams(Map<String, Integer> params) {
        List<scala.Tuple2<String, Object>> list = new ArrayList<>(params.size());
        for (Map.Entry<String, Integer> e : params.entrySet()) {
            list.add(scala.Tuple2.apply(e.getKey(), (Object) e.getValue()));
        }
        scala.collection.immutable.Seq<scala.Tuple2<String, Object>> scalaSeq =
                JavaConverters.asScalaIteratorConverter(list.iterator()).asScala().toSeq();

        return new DBCallActionBuilder(wrapped.outParams(scalaSeq));
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
        return this.wrapped;
    }
}
