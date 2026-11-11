package com.zinnia.regression.connector.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("!'${ngc.mongo.uri:}'.isEmpty()")
public class MongoConfig {

    @Bean(destroyMethod = "close")
    MongoClient ngcMongoClient(@Value("${ngc.mongo.uri}") String uri, @Value("${ngc.mongo.database:}") String database,
                               @Value("${ngc.mongo.connect-timeout-ms:5000}") long connectTimeoutMs,
                               @Value("${ngc.mongo.socket-timeout-ms:30000}") long socketTimeoutMs,
                               @Value("${ngc.mongo.server-selection-timeout-ms:10000}") long serverSelectionTimeoutMs,
                               @Value("${ngc.mongo.read-preference:secondaryPreferred}") String readPreference) {
        // a mongodb+srv://user:pass@host/?options URI carries no database name, so it is configured separately
        if (database == null || database.isBlank()) {
            throw new IllegalStateException("MONGODB_DATABASE must be set next to MONGODB_URI (the URI itself has no database name)");
        }
        return MongoClients.create(settings(uri, connectTimeoutMs, socketTimeoutMs, serverSelectionTimeoutMs, readPreference));
    }

    @Bean
    MongoLimits mongoLimits(@Value("${ngc.mongo.max-time-ms:15000}") long maxTimeMs) {
        return new MongoLimits(Duration.ofMillis(maxTimeMs));
    }

    static MongoClientSettings settings(String uri, long connectTimeoutMs, long socketTimeoutMs,
                                        long serverSelectionTimeoutMs, String readPreference) {
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToSocketSettings(b -> b.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(serverSelectionTimeoutMs, TimeUnit.MILLISECONDS))
                .readPreference(ReadPreference.valueOf(readPreference))
                .build();
    }
}
