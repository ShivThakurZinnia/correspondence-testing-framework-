package com.zinnia.regression.connector.mongo;

import com.mongodb.client.MongoClient;
import com.zinnia.regression.connector.Connector;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("!'${ngc.mongo.uri:}'.isEmpty()")
public class MongoConnector implements Connector {

    private final MongoClient client;
    private final String database;

    public MongoConnector(MongoClient client, @Value("${ngc.mongo.database}") String database) {
        this.client = client;
        this.database = database;
    }

    @Override
    public String id() {
        return "mongo";
    }

    @Override
    public Status check() {
        try {
            client.getDatabase(database).runCommand(new Document("ping", 1));
            return new Status(State.OK, "Database " + database + " answers");
        } catch (RuntimeException e) {
            return new Status(State.FAILED, "Cannot reach database " + database + " ("
                    + (MongoLimits.isTimeout(e) ? "timed out" : e.getClass().getSimpleName()) + ")");
        }
    }
}
