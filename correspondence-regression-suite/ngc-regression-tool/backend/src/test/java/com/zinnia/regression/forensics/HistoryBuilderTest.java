package com.zinnia.regression.forensics;

import static org.assertj.core.api.Assertions.assertThat;

import com.zinnia.regression.forensics.ForensicsModel.History;
import com.zinnia.regression.forensics.ForensicsModel.Stage;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class HistoryBuilderTest {

    static final String CID = "11111111-2222-3333-4444-555555555555";
    final HistoryBuilder builder = new HistoryBuilder();

    static Date t(String iso) {
        return Date.from(Instant.parse(iso));
    }

    static Document incoming(String uniqueId, String status, String category, String created) {
        return new Document("uniqueId", uniqueId).append("correlationId", CID).append("carrier", "GILI")
                .append("eventType", "policy.activation.completed").append("policyNumber", "AIUL300626252").append("planCode", "GCFIA903")
                .append("status", status).append("errorCategory", category).append("createdAt", t(created)).append("updatedAt", t(created))
                .append("targetRoleAndEventStatus", List.of(new Document("targetRole", "OWNER").append("targetEvent", "pdf")
                        .append("targetEventStatus", "SENT_TO_CDS").append("deliveryType", "PRINT")));
    }

    Map<String, List<Document>> raw(Document... docs) {
        Map<String, List<Document>> raw = new LinkedHashMap<>();
        for (Document d : docs) {
            raw.computeIfAbsent(d.getString("_c"), k -> new java.util.ArrayList<>()).add(d);
        }
        raw.values().forEach(l -> l.forEach(d -> d.remove("_c")));
        return raw;
    }

    static Document in(String collection, Document d) {
        return d.append("_c", collection);
    }

    @Test
    void aHealthyEventShowsEveryStageDoneAndNoMarkers() {
        History h = builder.build(CID, null, raw(
                in("incoming_events", incoming("u1", "SENT_TO_CDS", null, "2026-10-28T10:00:00Z")),
                in("cds_events", new Document("targetRole", "OWNER").append("originalUniqueId", "u1").append("createdAt", t("2026-10-28T10:00:05Z"))),
                in("internal_events", new Document("eventType", "pdf.generation.started").append("status", "PROCESSED").append("createdAt", t("2026-10-28T10:01:00Z"))),
                in("internal_events", new Document("eventType", "pdf.generation.completed").append("status", "PROCESSED").append("createdAt", t("2026-10-28T10:02:00Z"))),
                in("internal_events", new Document("eventType", "delivery.sent.print").append("status", "PROCESSED").append("createdAt", t("2026-10-28T10:03:00Z")))));

        assertThat(h.stages()).extracting(Stage::state).containsExactly("done", "done", "done", "done", "done");
        assertThat(h.markers()).isEmpty();
        assertThat(h.attempts()).hasSize(1);
        assertThat(h.attempts().get(0).policyNumber()).isEqualTo("AIUL300626252");
        assertThat(h.timeline()).extracting(e -> e.at()).isSorted();
    }

    @Test
    void aFailedEventShowsTheFailureReasonWorkerAndRetryMarkers() {
        Document failed = incoming("u1", "FAILED", "TECHNICAL_FAILURE", "2026-10-28T10:00:00Z")
                .append("errorMessage", "Zahara 404 for jane@example.com\n\tat com.zinnia.X.y(X.java:1)");
        History h = builder.build(CID, null, raw(
                in("incoming_events", failed),
                in("technical_failures", new Document("workerName", "CarrierDetailsWorker").append("errorMessage", "boom ssn=123-45-6789")
                        .append("uniqueId", "u1").append("createdAt", t("2026-10-28T10:00:02Z"))),
                in("technical_failures", new Document("errorMessage", "other").append("uniqueId", "u1").append("createdAt", t("2026-10-28T10:00:03Z"))),
                in("technical_failure_retries", new Document("workerName", "CarrierDetailsWorker").append("status", "RETRY_ATTEMPT_3")
                        .append("attemptCount", 3).append("uniqueId", "u1").append("lastAttemptAt", t("2026-10-28T12:00:00Z")))));

        assertThat(h.attempts().get(0).error()).isEqualTo("Zahara 404 for [email]");
        assertThat(h.stages().get(1).state()).isEqualTo("failed");
        assertThat(h.timeline()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("technical failure");
            assertThat(e.detail()).contains("worker: CarrierDetailsWorker").contains("ssn=[redacted]");
        });
        assertThat(h.timeline()).anySatisfy(e -> assertThat(e.detail()).contains("worker: not recorded"));
        assertThat(h.retries()).hasSize(1);
        assertThat(h.markers()).anyMatch(m -> m.contains("reset by automatic retries (up to 3"))
                .anyMatch(m -> m.contains("1 technical failure(s) have no worker name"));
    }

    @Test
    void replayedEventsListEveryAttemptAndSayTheyShareACorrelationId() {
        History h = builder.build(CID, null, raw(
                in("incoming_events", incoming("u2", "SENT_TO_CDS", null, "2026-10-28T11:00:00Z")),
                in("incoming_events", incoming("u1", "FAILED", "TECHNICAL_FAILURE", "2026-10-28T10:00:00Z"))));

        assertThat(h.attempts()).extracting(a -> a.uniqueId()).containsExactly("u1", "u2");
        assertThat(h.markers()).contains("Replayed: 2 processing attempts share this correlationId.");
    }

    @Test
    void aDeadLetterOnlyEventIsFlaggedAsNeverPersisted() {
        History h = builder.build(CID, null, raw(
                in("dead_letter_events", new Document("status", "PENDING_REPLAY").append("failureExceptionClass", "RuleExecutionException")
                        .append("failureReason", "rule engine 503").append("createdAt", t("2026-10-28T10:00:00Z")))));

        assertThat(h.attempts()).isEmpty();
        assertThat(h.markers()).contains("Never persisted: captured as a dead letter before an incoming event was created.");
        assertThat(h.stages().get(0).state()).isEqualTo("failed");
        assertThat(h.timeline().get(0).detail()).contains("RuleExecutionException").contains("rule engine 503");
    }

    @Test
    void anInvalidRequestIsFlaggedAsNeverPersisted() {
        History h = builder.build(CID, null, raw(
                in("event_journey", new Document("eventType", HistoryBuilder.INVALID_JOURNEY).append("createdAt", t("2026-10-28T10:00:00Z")))));

        assertThat(h.markers()).contains("Never persisted: rejected as invalid before processing.");
    }

    @Test
    void executionStepsAndXmlValidationAreSummarisedWithoutInputs() {
        History h = builder.build(CID, null, raw(
                in("incoming_events", incoming("u1", "FAILED", "BUSINESS_FAILURE", "2026-10-28T10:00:00Z")),
                in("incoming_event_execution_sequence", new Document("uniqueId", "u1").append("updatedAt", t("2026-10-28T10:00:01Z"))
                        .append("incomingEventExecutionSequence", List.of(
                                new Document("ruleName", "TargetRoleDeliveryType").append("status", "PASSED"),
                                new Document("apiEndpoint", "/policy/AIUL1").append("status", "FAILED")))),
                in("incoming_events_xml_validation_result", new Document("uniqueId", "u1").append("createdAt", t("2026-10-28T10:00:02Z"))
                        .append("rule_execution_fail_results", List.of(new Document("ruleId", "R-12").append("reason", "missing OWNER")))
                        .append("rule_execution_error_results", List.of()))));

        assertThat(h.timeline()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("execution steps");
            assertThat(e.status()).isEqualTo("FAILED");
            assertThat(e.detail()).isEqualTo("2 step(s) in the last attempt; failed: /policy/AIUL1");
        });
        assertThat(h.timeline()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("XML validation");
            assertThat(e.status()).isEqualTo("FAIL");
            assertThat(e.detail()).isEqualTo("1 failed, 0 errored: R-12: missing OWNER");
        });
        assertThat(h.markers()).contains("Execution steps are from the last attempt only; earlier attempts were overwritten.");
    }

    @Test
    void searchRowsCarryTargetsAndSanitizedErrors() {
        var s = builder.summary(incoming("u1", "FAILED", "BUSINESS_FAILURE", "2026-10-28T10:00:00Z").append("errorMessage", "no owner x@y.org"));

        assertThat(s.error()).isEqualTo("no owner [email]");
        assertThat(s.targets()).singleElement().satisfies(t -> {
            assertThat(t.role()).isEqualTo("OWNER");
            assertThat(t.deliveryType()).isEqualTo("PRINT");
        });
    }

    @Test
    void rejectionsReadFromDeadLettersAndInvalidJourneyRows() {
        var dead = builder.deadLetter(new Document("correlationId", CID).append("carrier", "GILI").append("status", "PENDING_REPLAY")
                .append("failureExceptionClass", "RuleExecutionException").append("failureReason", "503").append("createdAt", t("2026-10-28T10:00:00Z")));
        var invalid = builder.invalid(new Document("correlationId", CID).append("carrier", "GILI").append("originalEventType", "policy.x")
                .append("createdAt", t("2026-10-28T10:00:00Z")));

        assertThat(dead.kind()).isEqualTo("DEAD_LETTER");
        assertThat(dead.reason()).isEqualTo("RuleExecutionException: 503");
        assertThat(invalid.kind()).isEqualTo("INVALID");
        assertThat(invalid.eventType()).isEqualTo("policy.x");
    }

    @Test
    void anEmptyRawMapIsEmpty() {
        assertThat(builder.isEmpty(Map.of("incoming_events", List.of(), "cds_events", List.of()))).isTrue();
    }

    private Map<String, List<Document>> replayed() {
        return raw(
                in("incoming_events", incoming("u1", "FAILED", "TECHNICAL_FAILURE", "2026-10-28T10:00:00Z")),
                in("incoming_events", incoming("u2", "SENT_TO_CDS", null, "2026-10-28T11:00:00Z")),
                in("technical_failures", new Document("workerName", "CarrierDetailsWorker").append("errorMessage", "first try")
                        .append("uniqueId", "u1").append("createdAt", t("2026-10-28T10:00:02Z"))),
                in("cds_events", new Document("targetRole", "OWNER").append("originalUniqueId", "u2").append("createdAt", t("2026-10-28T11:00:05Z"))),
                in("internal_events", new Document("eventType", "pdf.generation.started").append("status", "PROCESSED")
                        .append("createdAt", t("2026-10-28T11:01:00Z"))));
    }

    @Test
    void severalAttemptsDefaultToTheLatestOneAndListAllOfThem() {
        History h = builder.build(CID, null, replayed());

        assertThat(h.selectedAttempt()).isEqualTo("u2");
        assertThat(h.attemptCount()).isEqualTo(2);
        assertThat(h.attempts()).extracting(a -> a.uniqueId()).containsExactly("u1", "u2");
        assertThat(h.timeline()).noneMatch(e -> "technical_failures".equals(e.collection()));
        assertThat(h.stages().get(1).state()).isEqualTo("done");
        assertThat(h.markers()).contains("Callbacks (internal events) cannot be tied to one attempt, so they are shown for every attempt.");
    }

    @Test
    void anExplicitAttemptOrAllAttemptsChangesWhatTheTimelineShows() {
        History first = builder.build(CID, "u1", replayed());
        History all = builder.build(CID, HistoryBuilder.ALL_ATTEMPTS, replayed());

        assertThat(first.selectedAttempt()).isEqualTo("u1");
        assertThat(first.timeline()).anyMatch(e -> "technical_failures".equals(e.collection()));
        assertThat(first.timeline()).noneMatch(e -> "cds_events".equals(e.collection()));
        assertThat(first.stages().get(1).state()).isEqualTo("failed");
        assertThat(all.selectedAttempt()).isNull();
        assertThat(all.timeline()).anyMatch(e -> "technical_failures".equals(e.collection()))
                .anyMatch(e -> "cds_events".equals(e.collection()));
        assertThat(all.markers()).doesNotContain("Callbacks (internal events) cannot be tied to one attempt, so they are shown for every attempt.");
    }

    @Test
    void hugeHistoriesAreCappedAndSaySo() {
        Map<String, List<Document>> raw = new LinkedHashMap<>();
        List<Document> callbacks = new java.util.ArrayList<>();
        for (int i = 0; i < HistoryBuilder.TIMELINE_LIMIT + 100; i++) {
            callbacks.add(new Document("eventType", "delivery.sent.print").append("status", "PROCESSED")
                    .append("createdAt", new Date(1_700_000_000_000L + i * 1000L)));
        }
        raw.put("internal_events", callbacks);
        List<Document> cds = new java.util.ArrayList<>();
        for (int i = 0; i < HistoryBuilder.ROW_LIMIT; i++) {
            cds.add(new Document("targetRole", "OWNER").append("createdAt", new Date(1_700_000_000_000L + i)));
        }
        raw.put("cds_events", cds);

        History h = builder.build(CID, null, raw);

        assertThat(h.timeline()).hasSize(HistoryBuilder.TIMELINE_LIMIT);
        assertThat(h.timeline()).extracting(e -> e.at()).isSorted();
        assertThat(h.markers()).anyMatch(m -> m.startsWith("Only the latest 500 of 800 timeline rows"))
                .contains("Only the latest 200 rows of cds_events were read; older rows are not shown.");
    }
}
