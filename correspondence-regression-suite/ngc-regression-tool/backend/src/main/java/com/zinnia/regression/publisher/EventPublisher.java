package com.zinnia.regression.publisher;

public interface EventPublisher {

    /** Publishes and blocks until the broker acknowledges. */
    PublishResult publish(String topic, String key, String value);

    record PublishResult(int partition, long offset) {
    }
}
