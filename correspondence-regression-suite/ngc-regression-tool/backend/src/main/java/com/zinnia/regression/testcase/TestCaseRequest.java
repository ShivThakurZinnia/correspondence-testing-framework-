package com.zinnia.regression.testcase;

import java.util.List;

public record TestCaseRequest(
        String name,
        String description,
        String kafkaKind,
        String kafkaTopicPattern,
        String eventType,
        String carrier,
        List<String> planCodes,
        Long payloadArtifactId,
        Long goldenArtifactId,
        String expectedJson,
        Integer timeoutSeconds,
        Boolean enabled,
        List<String> tags,
        String changeNote) {
}
