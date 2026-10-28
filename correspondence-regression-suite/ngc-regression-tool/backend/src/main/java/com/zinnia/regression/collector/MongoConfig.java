package com.zinnia.regression.collector;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("!'${ngc.mongo.uri:}'.isEmpty()")
public class MongoConfig {

    @Bean(destroyMethod = "close")
    MongoClient ngcMongoClient(@Value("${ngc.mongo.uri}") String uri, @Value("${ngc.mongo.database:}") String database) {
        // a mongodb+srv://user:pass@host/?options URI carries no database name, so it is configured separately
        if (database == null || database.isBlank()) {
            throw new IllegalStateException("MONGODB_DATABASE must be set next to MONGODB_URI (the URI itself has no database name)");
        }
        return MongoClients.create(uri);
    }
}
