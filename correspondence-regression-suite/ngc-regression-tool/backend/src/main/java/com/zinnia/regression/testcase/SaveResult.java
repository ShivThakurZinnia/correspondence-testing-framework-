package com.zinnia.regression.testcase;

/** Outcome of saving a test case: whether anything changed and the module version that now contains it. */
public record SaveResult(
        long testCaseId,
        long testCaseVersionId,
        int revision,
        boolean changed,
        long moduleVersionId,
        String moduleVersion) {
}
