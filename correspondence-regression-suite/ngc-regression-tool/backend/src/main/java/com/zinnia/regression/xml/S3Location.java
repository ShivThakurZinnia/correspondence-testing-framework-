package com.zinnia.regression.xml;

import java.util.Optional;

public record S3Location(String bucket, String key) {

    /** Parses s3://bucket/path/to/object; empty for anything else. */
    public static Optional<S3Location> parse(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            return Optional.empty();
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        if (slash < 1 || slash == rest.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new S3Location(rest.substring(0, slash), rest.substring(slash + 1)));
    }
}
