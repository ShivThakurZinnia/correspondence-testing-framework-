package com.zinnia.regression.connector.s3;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Read-only window onto the bucket where NGC writes generated XML and where XSDs live. */
public interface ObjectStore {

    String defaultBucket();

    /** Empty when the object does not exist; throws when the store cannot be reached (credentials, network). */
    Optional<byte[]> readBytes(String bucket, String key);

    default Optional<String> read(String bucket, String key) {
        return readBytes(bucket, key).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }
}
