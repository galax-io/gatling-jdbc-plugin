package io.cosmospf.load.javaapi;

import io.cosmospf.load.javaapi.actions.BatchInsertBaseAction;
import io.cosmospf.load.javaapi.actions.DBBaseAction;
import io.cosmospf.load.javaapi.protocol.JdbcProtocolBuilderBase;
import io.gatling.javaapi.core.internal.Expressions;
import io.cosmospf.load.javaapi.actions.BatchUpdateBaseAction;
import io.cosmospf.load.javaapi.check.simpleCheckType;
import io.cosmospf.load.jdbc.check.JdbcCheckSupport;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static scala.jdk.javaapi.CollectionConverters.asScala;

public final class JdbcDsl {
    private JdbcDsl(){}

    public static JdbcProtocolBuilderBase DB(){
        return new JdbcProtocolBuilderBase();
    }
    @Nonnull
    public static DBBaseAction jdbc(@Nonnull String name){
        return new DBBaseAction(io.cosmospf.load.jdbc.Predef.jdbc(Expressions.toStringExpression(name)));
    }

    @Nonnull
    public static BatchInsertBaseAction insetInto(@Nonnull String tableName, String... columns){
        return new BatchInsertBaseAction(io.cosmospf.load.jdbc.Predef.insertInto(Expressions.toStringExpression(tableName),
                new io.cosmospf.load.jdbc.actions.actions.Columns(
                        asScala(
                                Arrays
                                        .stream(columns)
                                        .toList())
                                .toSeq())));
    }

    @Nonnull
    public static BatchUpdateBaseAction update(@Nonnull String tableName){
        return new BatchUpdateBaseAction(io.cosmospf.load.jdbc.Predef.update(Expressions.toStringExpression(tableName)));
    }

    @Nonnull
    public static io.gatling.core.check.Check.Simple<scala.collection.immutable.List<scala.collection.immutable.Map<java.lang.String,java.lang.Object>>> simpleCheck(simpleCheckType checkType){
        return io.cosmospf.load.jdbc.internal.JdbcCheck.simpleJavaCheck(checkType);
    }

    @Nonnull
    public static io.gatling.core.check.CheckBuilder.Final<JdbcCheckSupport.JdbcAllRecordCheckType,scala.collection.immutable.List<scala.collection.immutable.Map<java.lang.String,java.lang.Object>>> allResults(){
        return io.cosmospf.load.jdbc.internal.JdbcCheck.results();
    }

}
