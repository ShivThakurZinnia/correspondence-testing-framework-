package com.zinnia.regression.health;

import com.zinnia.regression.config.WiredEnvironment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stores the headline numbers every 15 minutes so the Health page can draw trends. */
@Component
public class HealthSnapshotRecorder {

    private static final Logger log = LoggerFactory.getLogger(HealthSnapshotRecorder.class);

    private final ObjectProvider<HealthSource> source;
    private final HealthService service;
    private final WiredEnvironment wired;
    private final JdbcClient jdbc;
    private final int stuckMinutes;

    public HealthSnapshotRecorder(ObjectProvider<HealthSource> source, HealthService service, WiredEnvironment wired,
                                  JdbcClient jdbc, @Value("${regression.health.stuck-minutes:30}") int stuckMinutes) {
        this.source = source;
        this.service = service;
        this.wired = wired;
        this.jdbc = jdbc;
        this.stuckMinutes = stuckMinutes;
    }

    @Scheduled(fixedDelayString = "${regression.health.snapshot-ms:900000}", initialDelayString = "${regression.health.snapshot-ms:900000}")
    void scheduled() {
        HealthSource s = source.getIfAvailable();
        if (s == null || wired.code().isEmpty()) {
            return;
        }
        try {
            record(service.report(s, Instant.now(), 24, stuckMinutes), wired.code().get());
        } catch (RuntimeException e) {
            log.warn("Health snapshot failed: {}", e.getMessage());
        }
    }

    /** @return number of metrics stored */
    int record(HealthReport r, String env) {
        Long envId = jdbc.sql("SELECT id FROM environment WHERE code = :c").param("c", env).query(Long.class).optional().orElse(null);
        if (envId == null) {
            return 0;
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("STUCK_INCOMING", (double) r.stuck().incomingStuck());
        metrics.put("STUCK_CALLBACKS", (double) r.stuck().internalStuck());
        metrics.put("TIMED_OVERDUE", (double) r.stuck().timedOverdueRecent());
        metrics.put("EVENTS_24H", (double) r.eventsInWindow());
        metrics.put("FAILED_24H", (double) r.failures().failed());
        if (r.failures().failureRate() != null) {
            metrics.put("FAILURE_RATE_24H", r.failures().failureRate());
        }
        metrics.put("RETRIES_EXHAUSTED", (double) r.retries().exhausted());
        metrics.put("DEAD_LETTER_PENDING", (double) r.deadLetters().pending());
        if (r.funnel().pdfCompletedRate() != null) {
            metrics.put("PDF_COMPLETED_RATE", r.funnel().pdfCompletedRate());
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(r.generatedAt(), ZoneOffset.UTC);
        metrics.forEach((k, v) -> jdbc.sql("""
                INSERT INTO health_snapshot (environment_id, captured_at, metric_key, metric_value)
                VALUES (:e, :t, :k, :v)""").param("e", envId).param("t", at).param("k", k).param("v", v).update());
        return metrics.size();
    }
}
