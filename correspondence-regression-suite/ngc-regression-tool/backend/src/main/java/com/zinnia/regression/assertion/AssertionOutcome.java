package com.zinnia.regression.assertion;

public record AssertionOutcome(String category, String path, String expected, String actual, boolean passed,
                               String message) {

    static AssertionOutcome pass(String category, String path, String expected, String actual) {
        return new AssertionOutcome(category, path, expected, actual, true, null);
    }

    static AssertionOutcome fail(String category, String path, String expected, String actual, String message) {
        return new AssertionOutcome(category, path, expected, actual, false, message);
    }
}
