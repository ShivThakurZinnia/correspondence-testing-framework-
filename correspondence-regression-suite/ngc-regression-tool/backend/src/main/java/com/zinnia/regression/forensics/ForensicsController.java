package com.zinnia.regression.forensics;

import com.zinnia.regression.forensics.ForensicsModel.Criteria;
import com.zinnia.regression.forensics.ForensicsModel.History;
import com.zinnia.regression.health.HealthController;
import com.zinnia.regression.health.HealthSource;
import com.zinnia.regression.testcase.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forensics")
public class ForensicsController {

    static final int MAX_WINDOW_HOURS = 24 * 90;
    static final int MAX_TEXT = 128;

    private final ObjectProvider<ForensicsSource> source;
    private final ObjectProvider<HealthSource> health;
    private final TreeBuilder trees;

    public ForensicsController(ObjectProvider<ForensicsSource> source, ObjectProvider<HealthSource> health, TreeBuilder trees) {
        this.source = source;
        this.health = health;
        this.trees = trees;
    }

    /** Carrier, event type and outcome counts for the window: the levels above the event list. */
    @GetMapping("/tree")
    public ResponseEntity<?> tree(@RequestParam(defaultValue = "24") int hours) {
        ForensicsSource s = source.getIfAvailable();
        HealthSource h = health.getIfAvailable();
        if (s == null || h == null) {
            return notConfigured();
        }
        int window = window(hours);
        Instant since = Instant.now().minus(Duration.ofHours(window));
        try {
            return ResponseEntity.ok(trees.build(window, h.statusCounts(since), s.rejectedCounts(since)));
        } catch (RuntimeException e) {
            return failed(e);
        }
    }

    @GetMapping("/events")
    public ResponseEntity<?> search(@RequestParam(required = false) String correlationId,
                                    @RequestParam(required = false) String uniqueId,
                                    @RequestParam(required = false) String policyNumber,
                                    @RequestParam(required = false) String carrier,
                                    @RequestParam(required = false) String eventType,
                                    @RequestParam(defaultValue = "any") String outcome,
                                    @RequestParam(defaultValue = "24") int hours,
                                    @RequestParam(required = false) String before,
                                    @RequestParam(defaultValue = "50") int limit) {
        List<String> errors = new ArrayList<>();
        Criteria criteria = new Criteria(text("correlationId", correlationId, errors), text("uniqueId", uniqueId, errors),
                text("policyNumber", policyNumber, errors), text("carrier", carrier, errors), text("eventType", eventType, errors),
                outcome(outcome, errors), Instant.now().minus(Duration.ofHours(window(hours))), cursor(before, errors),
                Math.max(1, Math.min(limit, 100)));
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        ForensicsSource s = source.getIfAvailable();
        if (s == null) {
            return notConfigured();
        }
        try {
            return ResponseEntity.ok(s.search(criteria));
        } catch (RuntimeException e) {
            return failed(e);
        }
    }

    @GetMapping("/events/{correlationId}")
    public ResponseEntity<?> history(@PathVariable String correlationId, @RequestParam(required = false) String uniqueId) {
        List<String> errors = new ArrayList<>();
        String id = text("correlationId", correlationId, errors);
        String attempt = text("uniqueId", uniqueId, errors);
        if (id == null || !errors.isEmpty()) {
            throw new ValidationException(errors.isEmpty() ? List.of("correlationId is required") : errors);
        }
        ForensicsSource s = source.getIfAvailable();
        if (s == null) {
            return notConfigured();
        }
        try {
            History history = s.history(id, attempt);
            return history == null
                    ? ResponseEntity.status(404).body(Map.of("valid", false, "errors", List.of("NGC holds nothing for this correlationId")))
                    : ResponseEntity.ok(history);
        } catch (RuntimeException e) {
            return failed(e);
        }
    }

    private static int window(int hours) {
        return Math.max(1, Math.min(hours, MAX_WINDOW_HOURS));
    }

    private static String text(String name, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > MAX_TEXT) {
            errors.add(name + " is longer than " + MAX_TEXT + " characters");
            return null;
        }
        return v;
    }

    private static String outcome(String value, List<String> errors) {
        String v = value == null ? "any" : value.strip().toLowerCase();
        if (!Outcomes.ALL.contains(v)) {
            errors.add("outcome must be one of " + String.join(", ", Outcomes.ALL.stream().sorted().toList()));
        }
        return v;
    }

    private static Instant cursor(String before, List<String> errors) {
        if (before == null || before.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(before.strip());
        } catch (DateTimeParseException e) {
            errors.add("before must be an ISO-8601 instant such as 2026-10-28T10:15:30Z");
            return null;
        }
    }

    private static ResponseEntity<?> notConfigured() {
        return ResponseEntity.status(503).body(Map.of("valid", false, "errors",
                List.of("Mongo is not configured (MONGODB_URI, MONGODB_DATABASE)")));
    }

    private static ResponseEntity<?> failed(RuntimeException e) {
        return ResponseEntity.status(502).body(Map.of("valid", false, "errors", List.of(HealthController.readError(e))));
    }
}
