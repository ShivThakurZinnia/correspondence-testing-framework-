package com.zinnia.regression.health;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only aggregates over NGC's collections. Implemented against MongoDB; faked in tests. */
public interface HealthSource {

    StuckData stuck(Instant now, Duration threshold, Duration staleAfter);

    List<EventStatusCount> statusCounts(Instant since);

    List<NameCount> topFailureWorkers(Instant since, int limit);

    /** technical_failure_retries by status over the whole collection (small). */
    Map<String, Long> retryStatuses();

    DeadLetterData deadLetters();

    FunnelData funnel(Instant since);

    List<NewEventType> newEventTypes(Instant since, Instant priorFrom);

    /** Newest incoming_events createdAt, or null when empty. */
    Instant lastIncoming();

    /**
     * Work that is not finished but not ancient either: PROCESSING / NEW_EVENT older than the threshold but younger than
     * staleAfter. Older leftovers are counted as stale so they do not drown the signal.
     */
    record StuckData(long incomingStuck, Instant oldestIncomingStuck, long incomingStale, long internalStuck,
                     long timedOverdueRecent, long timedOverdueOlder) {
    }

    /** incoming_events keep a parent status (e.g. FAILED) plus an errorCategory that says why. */
    record EventStatusCount(String carrier, String eventType, String status, String errorCategory, long count) {

        public EventStatusCount(String carrier, String eventType, String status, long count) {
            this(carrier, eventType, status, null, count);
        }
    }

    record NameCount(String name, long count) {
    }

    record DeadLetterData(Map<String, Long> byStatus, Instant oldestPending) {
    }

    record FunnelData(long sentToCds, long pdfStarted, long pdfCompleted, long delivery, List<Long> secondsToPdfStarted) {
    }

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record NewEventType(String carrier, String eventType, Instant firstSeen) {
    }
}
