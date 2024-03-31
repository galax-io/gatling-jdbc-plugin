package org.galaxio.gatling.javaapi.actions;

import io.gatling.javaapi.core.internal.Expressions;
import java.util.Arrays;
import static scala.jdk.javaapi.CollectionConverters.asScala;

public final class DBBaseAction{

    private final org.galaxio.gatling.jdbc.actions.actions.DBBaseAction wrapped;

    public DBBaseAction(org.galaxio.gatling.jdbc.actions.actions.DBBaseAction dbBaseAction){
        this.wrapped = dbBaseAction;
    }

    public DBInsertActionValuesStep insertInto(String tableName, String... columns){
        return new DBInsertActionValuesStep(
                wrapped.insertInto(
                        Expressions.toStringExpression(tableName),
                        new org.galaxio.gatling.jdbc.actions.actions.Columns(
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
                                    org.galaxio.gatling.jdbc.internal.BatchBase.toScalaBatch(x))
                            .toList()).toSeq()
            ));
    }
}
