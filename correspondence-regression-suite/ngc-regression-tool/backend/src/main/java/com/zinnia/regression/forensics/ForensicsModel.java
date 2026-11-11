package com.zinnia.regression.forensics;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

public final class ForensicsModel {

    private ForensicsModel() {
    }

    public record Criteria(String correlationId, String uniqueId, String policyNumber, String carrier, String eventType,
                           String outcome, Instant from, Instant before, int limit) {

        boolean exactId() {
            return correlationId != null || uniqueId != null;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Target(String role, String status, String deliveryType, String error) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventSummary(String uniqueId, String correlationId, String carrier, String eventType, String policyNumber,
                               String planCode, String source, String status, String errorCategory, String error,
                               boolean retryInProgress, Instant createdAt, Instant updatedAt, List<Target> targets) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Rejection(String kind, String correlationId, String carrier, String eventType, String policyNumber,
                            String status, String reason, Instant at) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SearchResult(List<EventSummary> events, List<Rejection> rejected, boolean hasMore, Instant nextBefore) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimelineEntry(Instant at, String collection, String type, String status, String role, String detail,
                                String attempt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Retry(String worker, String status, int attempts, Instant lastAttemptAt, Instant nextEligibleAt,
                        String attempt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Stage(String code, String label, String state, Instant at) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record History(String correlationId, String selectedAttempt, int attemptCount, List<EventSummary> attempts,
                          List<Stage> stages, List<TimelineEntry> timeline, List<Retry> retries, List<String> markers) {
    }

    public record RejectedCount(String carrier, String kind, long count) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventTypeNode(String eventType, long total, long failed, long technical, long business, long stuck, long ok) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CarrierNode(String carrier, long total, long failed, long rejectedDeadLetters, long rejectedInvalid,
                              List<EventTypeNode> eventTypes) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Tree(int windowHours, List<CarrierNode> carriers) {
    }
}
