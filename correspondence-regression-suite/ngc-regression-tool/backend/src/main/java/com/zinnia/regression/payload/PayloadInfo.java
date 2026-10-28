package com.zinnia.regression.payload;

import java.util.List;

public record PayloadInfo(String correlationId, String carrier, String eventType, String planCode, String policyNumber, List<String> errors) {

    public boolean valid() {
        return errors.isEmpty();
    }
}
