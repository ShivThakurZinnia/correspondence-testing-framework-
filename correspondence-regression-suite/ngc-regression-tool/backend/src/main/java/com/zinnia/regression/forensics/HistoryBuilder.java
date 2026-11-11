package com.zinnia.regression.forensics;

import com.zinnia.regression.forensics.ForensicsModel.EventSummary;
import com.zinnia.regression.forensics.ForensicsModel.History;
import com.zinnia.regression.forensics.ForensicsModel.Rejection;
import com.zinnia.regression.forensics.ForensicsModel.Retry;
import com.zinnia.regression.forensics.ForensicsModel.Stage;
import com.zinnia.regression.forensics.ForensicsModel.Target;
import com.zinnia.regression.forensics.ForensicsModel.TimelineEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.bson.Document;
import org.springframework.stereotype.Component;

/** Turns NGC documents into forensic views. Pure: no database access, never reads message bodies. */
@Component
public class HistoryBuilder {

    static final String INVALID_JOURNEY = "correspondence.generation.requestInvalid";
    static final int ROW_LIMIT = 200;
    static final int TIMELINE_LIMIT = 500;
    static final String ALL_ATTEMPTS = "all";

    public EventSummary summary(Document d) {
        List<Target> targets = new ArrayList<>();
        for (Document t : list(d, "targetRoleAndEventStatus")) {
            targets.add(new Target(str(t, "targetRole"), str(t, "targetEventStatus"), str(t, "deliveryType"),
                    TextSanitizer.clean(str(t, "errorMessage"))));
        }
        return new EventSummary(str(d, "uniqueId"), str(d, "correlationId"), str(d, "carrier"), str(d, "eventType"),
                str(d, "policyNumber"), str(d, "planCode"), str(d, "source"), str(d, "status"), str(d, "errorCategory"),
                TextSanitizer.clean(str(d, "errorMessage")), Boolean.TRUE.equals(d.get("retryAttemptInProgress")),
                at(d, "createdAt"), at(d, "updatedAt"), targets);
    }

    public Rejection deadLetter(Document d) {
        String reason = TextSanitizer.clean(str(d, "failureReason"));
        String exception = str(d, "failureExceptionClass");
        return new Rejection("DEAD_LETTER", str(d, "correlationId"), str(d, "carrier"), str(d, "eventType"),
                str(d, "policyNumber"), str(d, "status"),
                exception == null ? reason : reason == null ? exception : exception + ": " + reason,
                at(d, "lastAttemptAt", "createdAt"));
    }

    public Rejection invalid(Document d) {
        return new Rejection("INVALID", str(d, "correlationId"), str(d, "carrier"), str(d, "originalEventType"),
                str(d, "policyNumber"), null, "Rejected as invalid before processing", at(d, "createdAt"));
    }

    public boolean isEmpty(Map<String, List<Document>> raw) {
        return raw.values().stream().allMatch(List::isEmpty);
    }

    /** scope: a uniqueId to scope to, "all" for every attempt, or null for the latest attempt when there are several. */
    public History build(String correlationId, String scope, Map<String, List<Document>> raw) {
        List<EventSummary> allAttempts = docs(raw, "incoming_events").stream().map(this::summary)
                .sorted(Comparator.comparing(EventSummary::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        String selected = selected(scope, allAttempts);
        Predicate<Document> inScope = d -> selected == null || attempt(d) == null || attempt(d).equals(selected);

        List<Document> incoming = docs(raw, "incoming_events").stream().filter(inScope).toList();
        List<Document> cds = docs(raw, "cds_events").stream().filter(inScope).toList();
        List<Document> journey = docs(raw, "event_journey").stream().filter(inScope).toList();
        List<Document> internal = docs(raw, "internal_events");
        List<Document> failures = docs(raw, "technical_failures").stream().filter(inScope).toList();
        List<Document> retryDocs = docs(raw, "technical_failure_retries").stream().filter(inScope).toList();
        List<Document> deadLetters = docs(raw, "dead_letter_events").stream().filter(inScope).toList();
        List<Document> sequences = docs(raw, "incoming_event_execution_sequence").stream().filter(inScope).toList();
        List<Document> audit = docs(raw, "target_role_and_event_status_audit").stream().filter(inScope).toList();
        List<Document> timed = docs(raw, "timed_corro").stream().filter(inScope).toList();
        List<Document> xml = docs(raw, "incoming_events_xml_validation_result").stream().filter(inScope).toList();

        List<EventSummary> attempts = allAttempts.stream().filter(a -> selected == null || a.uniqueId().equals(selected)).toList();
        List<Retry> retries = retryDocs.stream().map(d -> new Retry(str(d, "workerName"), str(d, "status"),
                num(d, "attemptCount"), at(d, "lastAttemptAt", "createdAt"), at(d, "nextEligibleAt"), str(d, "uniqueId"))).toList();

        List<TimelineEntry> timeline = new ArrayList<>();
        for (Document d : incoming) {
            String attempt = str(d, "uniqueId");
            timeline.add(entry(at(d, "createdAt"), "incoming_events", str(d, "eventType"), "RECEIVED", null, null, attempt));
            String category = str(d, "errorCategory");
            String error = TextSanitizer.clean(str(d, "errorMessage"));
            timeline.add(entry(at(d, "updatedAt", "createdAt"), "incoming_events", "current status", str(d, "status"), null,
                    join(category, error), attempt));
            for (Document t : list(d, "targetRoleAndEventStatus")) {
                timeline.add(entry(at(d, "updatedAt", "createdAt"), "incoming_events", str(t, "targetEvent"),
                        str(t, "targetEventStatus"), str(t, "targetRole"),
                        join(str(t, "deliveryType"), TextSanitizer.clean(str(t, "errorMessage"))), attempt));
            }
        }
        audit.forEach(d -> timeline.add(entry(at(d, "createdAt"),
                "target_role_and_event_status_audit", "target status", str(d, "status"), str(d, "targetPartyRole"), null,
                attempt(d))));
        journey.forEach(d -> timeline.add(entry(at(d, "createdAt"), "event_journey", str(d, "eventType"),
                str(d, "publishStatus"), str(d, "targetRole"), str(d, "documentType"), attempt(d))));
        cds.forEach(d -> timeline.add(entry(at(d, "createdAt"), "cds_events", "sent to CDS", "SENT", str(d, "targetRole"),
                null, attempt(d))));
        internal.forEach(d -> timeline.add(entry(at(d, "updatedAt", "createdAt"), "internal_events", str(d, "eventType"),
                str(d, "status"), str(d, "targetRole"), TextSanitizer.clean(str(d, "errorMessage")), null)));
        failures.forEach(d -> timeline.add(entry(at(d, "createdAt"), "technical_failures", "technical failure",
                "TECHNICAL_FAILURE", null, join("worker: " + (str(d, "workerName") == null ? "not recorded" : str(d, "workerName")),
                        TextSanitizer.clean(str(d, "errorMessage"))), str(d, "uniqueId"))));
        retries.forEach(r -> timeline.add(entry(r.lastAttemptAt(), "technical_failure_retries", "automatic retry", r.status(),
                null, "worker: " + (r.worker() == null ? "not recorded" : r.worker()) + ", attempt " + r.attempts()
                        + (r.nextEligibleAt() == null ? "" : ", next eligible " + r.nextEligibleAt()), r.attempt())));
        deadLetters.forEach(d -> timeline.add(entry(at(d, "createdAt"), "dead_letter_events", "dead letter", str(d, "status"),
                null, join(str(d, "failureExceptionClass"), TextSanitizer.clean(str(d, "failureReason"))), attempt(d))));
        timed.forEach(d -> timeline.add(entry(at(d, "createdAt"), "timed_corro", str(d, "eventType"),
                str(d, "status"), null, "follow-up at " + str(d, "followupDateTime"), attempt(d))));
        sequences.forEach(d -> timeline.add(sequence(d)));
        xml.forEach(d -> timeline.add(xmlValidation(d)));
        timeline.sort(Comparator.comparing(TimelineEntry::at, Comparator.nullsLast(Comparator.naturalOrder())));
        boolean truncated = timeline.size() > TIMELINE_LIMIT;
        List<TimelineEntry> shown = truncated ? timeline.subList(timeline.size() - TIMELINE_LIMIT, timeline.size()) : timeline;

        List<String> markers = markers(allAttempts, attempts, retries, failures, deadLetters, journey, sequences);
        if (selected != null && !internal.isEmpty()) {
            markers.add("Callbacks (internal events) cannot be tied to one attempt, so they are shown for every attempt.");
        }
        if (truncated) {
            markers.add("Only the latest " + TIMELINE_LIMIT + " of " + timeline.size() + " timeline rows are shown.");
        }
        raw.forEach((collection, rows) -> {
            if (rows.size() >= ROW_LIMIT) {
                markers.add("Only the latest " + ROW_LIMIT + " rows of " + collection + " were read; older rows are not shown.");
            }
        });
        return new History(correlationId, selected, allAttempts.size(), allAttempts,
                stages(attempts, cds, journey, internal, deadLetters), List.copyOf(shown), retries, markers);
    }

    private static String selected(String attempt, List<EventSummary> allAttempts) {
        if (ALL_ATTEMPTS.equals(attempt)) {
            return null;
        }
        if (attempt != null) {
            return attempt;
        }
        return allAttempts.size() > 1 ? allAttempts.get(allAttempts.size() - 1).uniqueId() : null;
    }

    private TimelineEntry sequence(Document d) {
        List<Document> steps = list(d, "incomingEventExecutionSequence");
        List<String> failed = new ArrayList<>();
        for (Document s : steps) {
            if ("FAILED".equals(str(s, "status")) && failed.size() < 5) {
                String name = str(s, "ruleName") != null ? str(s, "ruleName") : str(s, "apiEndpoint");
                failed.add(TextSanitizer.clean(name));
            }
        }
        String detail = steps.size() + " step(s) in the last attempt" + (failed.isEmpty() ? "" : "; failed: " + String.join(", ", failed));
        return entry(at(d, "updatedAt", "createdAt"), "incoming_event_execution_sequence", "execution steps",
                failed.isEmpty() ? "PASSED" : "FAILED", null, detail, str(d, "uniqueId"));
    }

    private TimelineEntry xmlValidation(Document d) {
        List<Document> fail = list(d, "rule_execution_fail_results");
        List<Document> error = list(d, "rule_execution_error_results");
        List<String> shown = new ArrayList<>();
        for (Document r : concat(fail, error)) {
            if (shown.size() < 5) {
                shown.add(join(str(r, "ruleId"), TextSanitizer.clean(str(r, "reason"))));
            }
        }
        String detail = fail.size() + " failed, " + error.size() + " errored" + (shown.isEmpty() ? "" : ": " + String.join("; ", shown));
        return entry(at(d, "createdAt"), "incoming_events_xml_validation_result", "XML validation",
                fail.isEmpty() && error.isEmpty() ? "PASS" : "FAIL", null, detail, str(d, "uniqueId"));
    }

    private List<Stage> stages(List<EventSummary> attempts, List<Document> cds, List<Document> journey,
                               List<Document> internal, List<Document> deadLetters) {
        boolean received = !attempts.isEmpty();
        boolean failedParent = attempts.stream().anyMatch(a -> Outcomes.failed(a.status()));
        Instant receivedAt = attempts.stream().map(EventSummary::createdAt).filter(t -> t != null).min(Comparator.naturalOrder()).orElse(null);
        Instant cdsAt = earliest(cds, d -> true);
        if (cdsAt == null) {
            cdsAt = earliest(journey, d -> String.valueOf(d.get("eventType")).endsWith("requestSentToCDS"));
        }
        boolean sent = cdsAt != null;
        Stage cdsStage = new Stage("SENT_TO_CDS", "Sent to CDS", sent ? "done" : failedParent ? "failed" : "pending", cdsAt);
        return List.of(
                new Stage("RECEIVED", "Received", received ? "done" : (deadLetters.isEmpty() && journey.isEmpty() ? "pending" : "failed"), receivedAt),
                cdsStage,
                callback("PDF_STARTED", "PDF started", internal, sent, e -> e.equals("pdf.generation.started")),
                callback("PDF_COMPLETED", "PDF completed", internal, sent, e -> e.equals("pdf.generation.completed")),
                callback("DELIVERY", "Delivery", internal, sent, e -> e.startsWith("delivery.")));
    }

    private Stage callback(String code, String label, List<Document> internal, boolean sent, Predicate<String> match) {
        List<Document> hits = internal.stream().filter(d -> match.test(String.valueOf(d.get("eventType")))).toList();
        boolean failed = hits.stream().anyMatch(d -> "TECHNICAL_FAILURE".equals(str(d, "status")));
        boolean ok = hits.stream().anyMatch(d -> !"TECHNICAL_FAILURE".equals(str(d, "status")));
        return new Stage(code, label, ok ? "done" : failed ? "failed" : "pending", earliest(hits, d -> true));
    }

    private List<String> markers(List<EventSummary> allAttempts, List<EventSummary> attempts, List<Retry> retries,
                                 List<Document> failures, List<Document> deadLetters, List<Document> journey,
                                 List<Document> sequences) {
        List<String> out = new ArrayList<>();
        if (allAttempts.size() > 1) {
            out.add("Replayed: " + allAttempts.size() + " processing attempts share this correlationId.");
        }
        int maxAttempts = retries.stream().mapToInt(Retry::attempts).max().orElse(0);
        if (maxAttempts > 0) {
            out.add("Status was reset by automatic retries (up to " + maxAttempts
                    + " attempt(s)); earlier statuses of the same event were overwritten and are not kept.");
        }
        if (attempts.stream().anyMatch(EventSummary::retryInProgress)) {
            out.add("An automatic retry is in progress right now.");
        }
        if (!sequences.isEmpty()) {
            out.add("Execution steps are from the last attempt only; earlier attempts were overwritten.");
        }
        boolean invalid = journey.stream().anyMatch(d -> INVALID_JOURNEY.equals(str(d, "eventType")));
        if (allAttempts.isEmpty() && !deadLetters.isEmpty()) {
            out.add("Never persisted: captured as a dead letter before an incoming event was created.");
        }
        if (allAttempts.isEmpty() && invalid) {
            out.add("Never persisted: rejected as invalid before processing.");
        }
        long unnamed = failures.stream().filter(d -> str(d, "workerName") == null).count();
        if (unnamed > 0) {
            out.add(unnamed + " technical failure(s) have no worker name recorded.");
        }
        return out;
    }

    private static TimelineEntry entry(Instant at, String collection, String type, String status, String role, String detail,
                                       String attempt) {
        return new TimelineEntry(at, collection, type, status, role, detail, attempt);
    }

    private static String join(String a, String b) {
        return a == null ? b : b == null ? a : a + ": " + b;
    }

    private static String attempt(Document d) {
        return str(d, "originalUniqueId") != null ? str(d, "originalUniqueId") : str(d, "uniqueId");
    }

    private static Instant earliest(List<Document> docs, Predicate<Document> match) {
        return docs.stream().filter(match).map(d -> at(d, "createdAt")).filter(t -> t != null)
                .min(Comparator.naturalOrder()).orElse(null);
    }

    private static List<Document> docs(Map<String, List<Document>> raw, String collection) {
        return raw.getOrDefault(collection, List.of());
    }

    private static List<Document> concat(List<Document> a, List<Document> b) {
        List<Document> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static int num(Document d, String key) {
        return d.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Document d, String key) {
        Object v = d.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Document> list(Document d, String key) {
        Object v = d.get(key);
        return v instanceof List<?> l ? (List<Document>) l : List.of();
    }

    private static Instant at(Document d, String... keys) {
        for (String k : keys) {
            if (d.get(k) instanceof Date date) {
                return date.toInstant();
            }
        }
        return null;
    }
}
