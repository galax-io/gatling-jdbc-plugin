package org.galaxio.gatling.javaapi.internal;

import io.gatling.javaapi.core.internal.Expressions;

import java.util.Map;

import static scala.jdk.javaapi.CollectionConverters.asScala;

public final class Utils {
    public static scala.collection.immutable.Seq<scala.Tuple2<String, scala.Function1<io.gatling.core.session.Session, io.gatling.commons.validation.Validation<Object>>>> getSeq(Map<String, Object> values){
        return asScala(
                values
                        .entrySet()
                        .stream()
                        .map(pair -> {
                            Object value = pair.getValue();
                            scala.Function1<io.gatling.core.session.Session, io.gatling.commons.validation.Validation<Object>> expr;
                            if (value instanceof String) {
                                // String values may contain Gatling EL expressions (e.g. "#{sessionVar}"),
                                // so resolve them through the EL engine.
                                expr = Expressions.toExpression((String) value, Object.class);
                            } else {
                                // Non-string values (int, long, boolean, UUID, Timestamp, etc.) must be
                                // kept as-is to preserve the original Java type for correct JDBC binding.
                                expr = Expressions.toStaticValueExpression(value);
                            }
                            return scala.Tuple2.apply(pair.getKey(), expr);
                        }).toList()
                        .stream()
                        .toList())
                .toSeq();
    }
}
