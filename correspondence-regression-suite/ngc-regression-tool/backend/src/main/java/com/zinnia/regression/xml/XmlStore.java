package com.zinnia.regression.xml;

import java.util.Optional;

/** Read-only window onto the bucket where NGC writes generated XML and where XSDs live. */
public interface XmlStore {

    String defaultBucket();

    /** Empty when the object does not exist; throws when the store cannot be reached (credentials, network). */
    Optional<String> read(String bucket, String key);
}
