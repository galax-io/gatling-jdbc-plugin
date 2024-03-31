package org.galaxio.gatling.javaapi.actions;

import io.gatling.commons.validation.Validation;
import io.gatling.core.session.Session;
import org.galaxio.gatling.javaapi.internal.Utils;
import scala.Function1;
import java.util.Map;

public class BatchInsertAction implements BatchAction {

    public org.galaxio.gatling.jdbc.actions.actions.BatchInsertAction wrapped;

    public BatchInsertAction(org.galaxio.gatling.jdbc.actions.actions.BatchInsertAction batchInsertAction){
        this.wrapped = batchInsertAction;
    }

    public static org.galaxio.gatling.jdbc.actions.actions.BatchInsertAction toScala(
            Function1<Session, Validation<String>> tableName,
            Map<String, Object> values,
            org.galaxio.gatling.jdbc.actions.actions.Columns columns
    ){
        return new org.galaxio.gatling.jdbc.actions.actions.BatchInsertAction
                (tableName, columns, Utils.getSeq(values)
        );
    }
}
