package com.zinnia.regression.health;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthReport(
        Instant generatedAt,
        int windowHours,
        String level,
        Instant lastEventAt,
        long eventsInWindow,
        List<Issue> issues,
        Stuck stuck,
        Failures failures,
        List<HealthSource.NameCount> workers,
        Retries retries,
        DeadLetters deadLetters,
        Funnel funnel,
        List<HealthSource.NewEventType> newEventTypes) {

    public record Issue(String level, String text) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Stuck(String level, int thresholdMinutes, long incomingStuck, Long oldestIncomingMinutes,
                        long incomingStale, long internalStuck, long timedOverdueRecent, long timedOverdueOlder) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Failures(String level, long total, long failed, long technical, long business, Double failureRate,
                           List<ByEvent> byEvent) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ByEvent(String carrier, String eventType, long total, long failed, long technical, long business,
                          long sentToCds, long processed, Double failureRate) {
    }

    public record Retries(String level, long successful, long exhausted, long retrying) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeadLetters(String level, long pending, long exhausted, long replayed, Long oldestPendingMinutes) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Funnel(String level, long sentToCds, long pdfStarted, long pdfCompleted, long delivery,
                         Double pdfStartedRate, Double pdfCompletedRate, Long medianSecondsToPdfStarted,
                         Long p90SecondsToPdfStarted) {
    }
}
