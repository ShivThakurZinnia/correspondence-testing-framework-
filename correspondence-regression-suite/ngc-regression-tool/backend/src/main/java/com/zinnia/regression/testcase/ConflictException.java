package com.zinnia.regression.testcase;

/** The request is valid but not allowed in the current state (HTTP 409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
