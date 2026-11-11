package com.zinnia.regression.connector.mongo;

import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.EstimatedDocumentCountOptions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;

public final class MongoLimits {

    public static final MongoLimits DEFAULT = new MongoLimits(Duration.ofSeconds(15));

    private final long maxTimeMs;

    public MongoLimits(Duration maxTime) {
        this.maxTimeMs = maxTime.toMillis();
    }

    public <T> AggregateIterable<T> apply(AggregateIterable<T> query) {
        return query.maxTime(maxTimeMs, TimeUnit.MILLISECONDS);
    }

    public <T> FindIterable<T> apply(FindIterable<T> query) {
        return query.maxTime(maxTimeMs, TimeUnit.MILLISECONDS);
    }

    public <T> ListIndexesIterable<T> apply(ListIndexesIterable<T> query) {
        return query.maxTime(maxTimeMs, TimeUnit.MILLISECONDS);
    }

    public ListCollectionNamesIterable apply(ListCollectionNamesIterable query) {
        return query.maxTime(maxTimeMs, TimeUnit.MILLISECONDS);
    }

    public long estimatedCount(MongoCollection<Document> collection) {
        return collection.estimatedDocumentCount(new EstimatedDocumentCountOptions().maxTime(maxTimeMs, TimeUnit.MILLISECONDS));
    }

    public long count(MongoCollection<Document> collection, Bson filter) {
        return collection.countDocuments(filter, new CountOptions().maxTime(maxTimeMs, TimeUnit.MILLISECONDS));
    }

    public static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof MongoExecutionTimeoutException || t instanceof MongoSocketReadTimeoutException
                    || t instanceof MongoTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
