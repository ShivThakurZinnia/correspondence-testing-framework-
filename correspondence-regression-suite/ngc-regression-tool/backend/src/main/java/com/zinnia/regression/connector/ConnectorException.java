package com.zinnia.regression.connector;

public class ConnectorException extends RuntimeException {

    public enum Kind { UNAVAILABLE, NOT_FOUND, DENIED, TOO_LARGE }

    private final Kind kind;

    public ConnectorException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ConnectorException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
