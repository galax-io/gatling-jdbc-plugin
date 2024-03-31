package org.galaxio.gatling.javaapi;

import org.galaxio.gatling.javaapi.actions.BatchInsertBaseAction;
import org.galaxio.gatling.javaapi.actions.DBBaseAction;
import org.galaxio.gatling.javaapi.protocol.JdbcProtocolBuilderBase;
import io.gatling.javaapi.core.internal.Expressions;
import org.galaxio.gatling.javaapi.actions.BatchUpdateBaseAction;
import org.galaxio.gatling.javaapi.check.simpleCheckType;
import org.galaxio.gatling.jdbc.check.JdbcCheckSupport;

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
        return new DBBaseAction(org.galaxio.gatling.jdbc.Predef.jdbc(Expressions.toStringExpression(name)));
    }

    @Nonnull
    public static BatchInsertBaseAction insetInto(@Nonnull String tableName, String... columns){
        return new BatchInsertBaseAction(org.galaxio.gatling.jdbc.Predef.insertInto(Expressions.toStringExpression(tableName),
                new org.galaxio.gatling.jdbc.actions.actions.Columns(
                        asScala(
                                Arrays
                                        .stream(columns)
                                        .toList())
                                .toSeq())));
    }

    @Nonnull
    public static BatchUpdateBaseAction update(@Nonnull String tableName){
        return new BatchUpdateBaseAction(org.galaxio.gatling.jdbc.Predef.update(Expressions.toStringExpression(tableName)));
    }

    @Nonnull
    public static io.gatling.core.check.Check.Simple<scala.collection.immutable.List<scala.collection.immutable.Map<java.lang.String,java.lang.Object>>> simpleCheck(simpleCheckType checkType){
        return org.galaxio.gatling.jdbc.internal.JdbcCheck.simpleJavaCheck(checkType);
    }

    @Nonnull
    public static io.gatling.core.check.CheckBuilder.Final<JdbcCheckSupport.JdbcAllRecordCheckType,scala.collection.immutable.List<scala.collection.immutable.Map<java.lang.String,java.lang.Object>>> allResults(){
        return org.galaxio.gatling.jdbc.internal.JdbcCheck.results();
    }

}
