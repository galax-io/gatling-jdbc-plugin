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
                        .map(pair ->
                                scala.Tuple2.apply(pair.getKey(), toExpression(pair.getValue()))
                        ).toList()
                        .stream()
                        .toList())
                .toSeq();
    }

    private static scala.Function1<io.gatling.core.session.Session, io.gatling.commons.validation.Validation<Object>> toExpression(Object value) {
        if (value instanceof String stringValue) {
            return Expressions.toExpression(stringValue, Object.class);
        }
        return Expressions.toStaticValueExpression(value);
    }
}
