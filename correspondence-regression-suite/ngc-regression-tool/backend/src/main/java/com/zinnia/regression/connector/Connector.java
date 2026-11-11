package com.zinnia.regression.connector;

/** One external system the tool talks to. A new system is a new subpackage whose bean implements this. */
public interface Connector {

    enum State { OK, NOT_PROBED, FAILED }

    record Status(State state, String detail) {
    }

    String id();

    /** Cheap and read-only; never fetches business data. NOT_PROBED means configured but not verified by a call. */
    Status check();
}
