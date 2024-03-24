package io.cosmospf.load.javaapi.actions;

import io.gatling.commons.validation.Validation;
import io.gatling.core.session.Session;
import io.cosmospf.load.javaapi.internal.Utils;
import scala.Function1;
import java.util.Map;

public class BatchInsertAction implements BatchAction {

    public io.cosmospf.load.jdbc.actions.actions.BatchInsertAction wrapped;

    public BatchInsertAction(io.cosmospf.load.jdbc.actions.actions.BatchInsertAction batchInsertAction){
        this.wrapped = batchInsertAction;
    }

    public static io.cosmospf.load.jdbc.actions.actions.BatchInsertAction toScala(
            Function1<Session, Validation<String>> tableName,
            Map<String, Object> values,
            io.cosmospf.load.jdbc.actions.actions.Columns columns
    ){
        return new io.cosmospf.load.jdbc.actions.actions.BatchInsertAction
                (tableName, columns, Utils.getSeq(values)
        );
    }
}
