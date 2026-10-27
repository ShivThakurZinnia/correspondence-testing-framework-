package com.zinnia.regression.health;

import com.zinnia.regression.common.Rows;
import com.zinnia.regression.config.WiredEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final ObjectProvider<HealthSource> source;
    private final HealthService service;
    private final WiredEnvironment wired;
    private final JdbcClient jdbc;
    private final int defaultStuckMinutes;
    private final boolean warmUpOnStart;
    private static final long CACHE_SECONDS = 20;
    private final java.util.concurrent.ConcurrentHashMap<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private record Cached(Instant at, Map<String, Object> body) {
    }

    public HealthController(ObjectProvider<HealthSource> source, HealthService service, WiredEnvironment wired,
                            JdbcClient jdbc, @Value("${regression.health.stuck-minutes:30}") int defaultStuckMinutes,
                            @Value("${regression.health.warm-up-on-start:true}") boolean warmUpOnStart) {
        this.warmUpOnStart = warmUpOnStart;
        this.source = source;
        this.service = service;
        this.wired = wired;
        this.jdbc = jdbc;
        this.defaultStuckMinutes = defaultStuckMinutes;
    }

    /** The first query opens several database connections (several seconds); do that at startup, not on someone's first click. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void warmUp() {
        if (warmUpOnStart && source.getIfAvailable() != null) {
            Thread.ofVirtual().start(() -> health(168, null));
        }
    }

    /** Live health of the environment this instance is connected to. */
    @GetMapping
    public ResponseEntity<?> health(@RequestParam(defaultValue = "168") int windowHours,
                                    @RequestParam(required = false) Integer stuckMinutes) {
        HealthSource s = source.getIfAvailable();
        if (s == null) {
            return ResponseEntity.status(503).body(Map.of("valid", false, "errors",
                    List.of("Mongo is not configured (MONGODB_URI, MONGODB_DATABASE)")));
        }
        int window = Math.max(1, Math.min(windowHours, 24 * 90));
        int stuck = stuckMinutes == null ? defaultStuckMinutes : stuckMinutes;
        // several people (and auto-refresh) can look at once; one real computation per 20 seconds is plenty
        String key = window + "/" + stuck;
        Cached hit = cache.get(key);
        if (hit != null && Instant.now().isBefore(hit.at.plusSeconds(CACHE_SECONDS))) {
            return ResponseEntity.ok(hit.body);
        }
        try {
            HealthReport report = service.report(s, Instant.now(), window, stuck);
            Map<String, Object> body = Map.of("environment", wired.code().orElse(""), "report", report);
            cache.put(key, new Cached(Instant.now(), body));
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            return ResponseEntity.status(502).body(Map.of("valid", false, "errors",
                    List.of("Could not read the NGC database: " + e.getMessage())));
        }
    }

    /** Stored 15-minute snapshots for trend lines. */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam String metric, @RequestParam(defaultValue = "72") int hours) {
        return Rows.list(jdbc.sql("""
                SELECT captured_at, metric_value, dimension FROM health_snapshot
                WHERE metric_key = :m AND captured_at >= :since ORDER BY captured_at
                """).param("m", metric)
                .param("since", java.time.OffsetDateTime.now().minusHours(Math.min(hours, 24 * 30))));
    }
}
