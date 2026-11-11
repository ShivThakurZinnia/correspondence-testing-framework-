package com.zinnia.regression.forensics;

import java.util.List;
import java.util.Set;

final class Outcomes {

    static final Set<String> ALL = Set.of("any", "failed", "technical", "business", "stuck", "ok");
    static final List<String> STUCK_STATUSES = List.of("PROCESSING", "NEW_EVENT");
    static final List<String> OK_STATUSES = List.of("SENT_TO_CDS", "PROCESSED", "SENT_TO_MERRILL", "SENT_TO_EMAIL_SERVICE");
    static final List<String> TECHNICAL_CATEGORIES = List.of("TECHNICAL_FAILURE", "BUSINESS_AND_TECHNICAL_FAILURE");
    static final List<String> BUSINESS_CATEGORIES = List.of("BUSINESS_FAILURE", "BUSINESS_AND_TECHNICAL_FAILURE");

    private Outcomes() {
    }

    static boolean failed(String status) {
        return "FAILED".equals(status);
    }

    static boolean technical(String status, String category) {
        return failed(status) && category != null && TECHNICAL_CATEGORIES.contains(category);
    }

    static boolean business(String status, String category) {
        return failed(status) && category != null && BUSINESS_CATEGORIES.contains(category);
    }

    static boolean stuck(String status) {
        return STUCK_STATUSES.contains(status);
    }

    static boolean ok(String status) {
        return OK_STATUSES.contains(status);
    }
}
