package com.zinnia.regression.collector;

import java.time.OffsetDateTime;

/** One thing seen in NGC for a correlationId. Structural fields only: never the raw message body. */
public record Observation(
        OffsetDateTime occurredAt,
        String source,
        String collection,
        String eventType,
        String status,
        String targetRole,
        String ngcUniqueId,
        String tangoJobId,
        String roleTrackingId,
        String originalTargetEvent,
        String sourceDocumentId,
        String detailJson) {
}
