package com.zinnia.regression.collector;

import java.util.List;

/** Everything NGC holds for one correlationId at a point in time. */
public record NgcView(
        String correlationId,
        List<IncomingEventView> incomingEvents,
        int cdsEventCount,
        int technicalFailureCount,
        int deadLetterCount,
        List<Observation> observations,
        List<CdsPayload> cdsPayloads) {

    public NgcView(String correlationId, List<IncomingEventView> incomingEvents, int cdsEventCount,
                   int technicalFailureCount, int deadLetterCount, List<Observation> observations) {
        this(correlationId, incomingEvents, cdsEventCount, technicalFailureCount, deadLetterCount, observations,
                List.of());
    }

    /** Key/values NGC sent to CDS for one target (party name and id are deliberately not kept). */
    public record CdsPayload(String targetRole, java.util.Map<String, String> fields) {
    }

    public record IncomingEventView(
            String uniqueId,
            String status,
            String errorCategory,
            String s3FileBaseLocation,
            List<TargetView> targets) {
    }

    public record TargetView(
            String role,
            String status,
            String deliveryType,
            String emailNotification,
            String agentType,
            String documentType,
            String xmlFileName,
            String tangoJobId,
            String roleTrackingId) {
    }
}
