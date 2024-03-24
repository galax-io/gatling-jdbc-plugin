package io.cosmospf.load.javaapi.actions;

import io.gatling.javaapi.core.internal.Expressions;
import java.util.Arrays;
import static scala.jdk.javaapi.CollectionConverters.asScala;

public final class DBBaseAction{

    private final io.cosmospf.load.jdbc.actions.actions.DBBaseAction wrapped;

    public DBBaseAction(io.cosmospf.load.jdbc.actions.actions.DBBaseAction dbBaseAction){
        this.wrapped = dbBaseAction;
    }

    public DBInsertActionValuesStep insertInto(String tableName, String... columns){
        return new DBInsertActionValuesStep(
                wrapped.insertInto(
                        Expressions.toStringExpression(tableName),
                        new io.cosmospf.load.jdbc.actions.actions.Columns(
                                asScala(
                                        Arrays
                                                .stream(columns)
                                                .toList())
                                        .toSeq()
                        )));
    }

    public DBCallActionParamStep call(String procedureName){
        return new DBCallActionParamStep(wrapped.call(Expressions.toStringExpression(procedureName)));
    }

    public RawSqlActionBuilder rawSql(String queryString){
        return new RawSqlActionBuilder(wrapped.rawSql(Expressions.toStringExpression(queryString)));
    }

    public QueryActionParamsStep queryP(String sql){
        return new QueryActionParamsStep(wrapped.queryP(Expressions.toStringExpression(sql)));
    }

    public QueryActionBuilder query(String sql) {
        return new QueryActionBuilder(wrapped.query(Expressions.toStringExpression(sql)));
    }

    public BatchActionBuilder batch(BatchAction... actions) {
        return new BatchActionBuilder(
            wrapped.batch(
                    asScala(Arrays.stream(actions).map(x ->
                                    io.cosmospf.load.jdbc.internal.BatchBase.toScalaBatch(x))
                            .toList()).toSeq()
            ));
    }
}
