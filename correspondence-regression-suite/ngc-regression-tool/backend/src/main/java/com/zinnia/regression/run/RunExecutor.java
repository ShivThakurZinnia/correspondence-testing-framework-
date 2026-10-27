package com.zinnia.regression.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.assertion.AssertionEngine;
import com.zinnia.regression.assertion.AssertionOutcome;
import com.zinnia.regression.collector.NgcDataSource;
import com.zinnia.regression.collector.NgcView;
import com.zinnia.regression.collector.ObservationRecorder;
import com.zinnia.regression.payload.CorrelationIdRewriter;
import com.zinnia.regression.payload.EventPayloadValidator;
import com.zinnia.regression.payload.PayloadInfo;
import com.zinnia.regression.payload.PlanCodeRewriter;
import com.zinnia.regression.publisher.EventPublisher;
import com.zinnia.regression.xml.XmlStore;
import com.zinnia.regression.xml.XmlVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Plays one run, built for hundreds or thousands of events:
 * <ul>
 *   <li>publishes at most {@code max-in-flight} unfinished events and {@code events-per-second} per second,
 *       with a global cap shared by all runs;</li>
 *   <li>watches every in-flight event with one batched Mongo query per cycle;</li>
 *   <li>never runs two events of the same policy at once;</li>
 *   <li>keeps all state in the database, so an unfinished run resumes after a restart;</li>
 *   <li>can be cancelled, and aborts itself when publishing keeps failing.</li>
 * </ul>
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    private final JdbcClient jdbc;
    private final ArtifactRepository artifacts;
    private final CorrelationIdRewriter rewriter;
    private final PlanCodeRewriter planRewriter;
    private final EventPayloadValidator payloads;
    private final AssertionEngine assertions;
    private final ObservationRecorder recorder;
    private final ObjectProvider<NgcDataSource> ngc;
    private final ObjectProvider<EventPublisher> publisher;
    private final ObjectProvider<XmlStore> xmlStore;
    private final XmlVerifier xmlVerifier;
    private final DispatchGate gate;
    private final ObjectMapper json;
    private final long pollIntervalMs;
    private final int defaultMaxInFlight;
    private final double defaultEventsPerSecond;
    private final int publishFailureLimit;
    private final Set<String> allowedCarriers;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService runPool = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService finishPool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore finishers = new Semaphore(8);

    public RunExecutor(JdbcClient jdbc, ArtifactRepository artifacts, CorrelationIdRewriter rewriter, PlanCodeRewriter planRewriter,
                       EventPayloadValidator payloads, AssertionEngine assertions, ObservationRecorder recorder,
                       ObjectProvider<NgcDataSource> ngc, ObjectProvider<EventPublisher> publisher,
                       ObjectProvider<XmlStore> xmlStore, XmlVerifier xmlVerifier, DispatchGate gate, ObjectMapper json,
                       @Value("${regression.poll-interval-ms:5000}") long pollIntervalMs,
                       @Value("${regression.run.max-in-flight:25}") int defaultMaxInFlight,
                       @Value("${regression.run.events-per-second:5}") double defaultEventsPerSecond,
                       @Value("${regression.run.publish-failure-limit:5}") int publishFailureLimit,
                       @Value("${regression.allowed-carriers:}") String allowedCarriers) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.rewriter = rewriter;
        this.planRewriter = planRewriter;
        this.payloads = payloads;
        this.assertions = assertions;
        this.recorder = recorder;
        this.ngc = ngc;
        this.publisher = publisher;
        this.xmlStore = xmlStore;
        this.xmlVerifier = xmlVerifier;
        this.gate = gate;
        this.json = json;
        this.pollIntervalMs = pollIntervalMs;
        this.defaultMaxInFlight = defaultMaxInFlight;
        this.defaultEventsPerSecond = defaultEventsPerSecond;
        this.publishFailureLimit = publishFailureLimit;
        this.allowedCarriers = Set.copyOf(Arrays.stream(allowedCarriers.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
    }

    /** Runs in the background; progress is visible through the run query API while it executes. */
    public Future<?> executeAsync(String runKey) {
        if (!active.add(runKey)) {
            return CompletableFuture.completedFuture(null);
        }
        return runPool.submit(() -> {
            try {
                execute(runKey);
            } catch (RuntimeException e) {
                log.error("Run {} failed unexpectedly", runKey, e);
            } finally {
                active.remove(runKey);
            }
        });
    }

    /** Everything is in the database, so a run that was cut off by a restart simply carries on. */
    @EventListener(ApplicationReadyEvent.class)
    void resumeUnfinishedRuns() {
        for (String key : jdbc.sql("SELECT run_key FROM test_run WHERE status IN ('QUEUED','RUNNING') ORDER BY id")
                .query(String.class).list()) {
            log.info("Resuming run {} after restart", key);
            executeAsync(key);
        }
    }

    // ------------------------------------------------------------------------------------------------ run loop

    private record RunInfo(long id, String env, int maxInFlight, double eventsPerSecond) {
    }

    private record Pending(long runCaseId, String serialKey) {
    }

    private record Def(long runCaseId, String correlationId, String serialKey, long payloadArtifact, String kind,
                       String pattern, String carrier, String expected, int timeout, String originalId,
                       Long goldenArtifact, String planCode) {
    }

    private record Flight(Def def, JsonNode expected, Instant deadline) {
    }

    void execute(String runKey) {
        RunInfo run = jdbc.sql("""
                SELECT r.id, e.code, r.max_in_flight, r.events_per_second FROM test_run r
                JOIN environment e ON e.id = r.environment_id WHERE r.run_key = :k
                """).param("k", runKey).query((rs, n) -> new RunInfo(rs.getLong(1), rs.getString(2),
                rs.getObject(3) == null ? defaultMaxInFlight : rs.getInt(3),
                rs.getObject(4) == null ? defaultEventsPerSecond : rs.getDouble(4))).single();
        jdbc.sql("UPDATE test_run SET status = 'RUNNING', started_at = COALESCE(started_at, CURRENT_TIMESTAMP) WHERE id = :r")
                .param("r", run.id()).update();

        EventPublisher kafka = publisher.getIfAvailable();
        NgcDataSource source = ngc.getIfAvailable();
        if (kafka == null || source == null) {
            skipOrFail(run.id(), "PENDING", "ERROR", "Kafka and Mongo must be configured (KAFKA_BOOTSTRAP_SERVER, MONGODB_URI)");
            complete(run.id(), false, false);
            return;
        }

        Deque<Pending> pending = new ArrayDeque<>(jdbc.sql(
                "SELECT id, serial_key FROM test_run_case WHERE run_id = :r AND status = 'PENDING' ORDER BY id")
                .param("r", run.id()).query((rs, n) -> new Pending(rs.getLong(1), rs.getString(2))).list());
        Map<Long, Flight> inFlight = new LinkedHashMap<>();
        Set<String> activeKeys = new HashSet<>();
        resumePublished(run.id(), inFlight, activeKeys);

        List<Future<?>> finishing = new ArrayList<>();
        long gapNanos = (long) (1_000_000_000L / Math.max(0.1, run.eventsPerSecond()));
        long pollNanos = Duration.ofMillis(pollIntervalMs).toNanos();
        long tickMs = Math.max(5, Math.min(pollIntervalMs, 100));
        long nextPublishAt = 0;
        long lastPoll = 0;
        long lastCancelCheck = 0;
        long lastCounters = 0;
        int consecutiveFailures = 0;
        boolean aborted = false;
        boolean cancelled = false;

        while (true) {
            long now = System.nanoTime();
            if (now - lastCancelCheck >= 500_000_000L) {
                lastCancelCheck = now;
                if (cancelRequested(run.id())) {
                    cancelled = true;
                    break;
                }
            }

            while (!aborted && !pending.isEmpty() && inFlight.size() < run.maxInFlight() && System.nanoTime() >= nextPublishAt) {
                Pending next = pickNext(pending, activeKeys);
                if (next == null || !gate.tryAcquire()) {
                    break;
                }
                nextPublishAt = System.nanoTime() + gapNanos;
                try {
                    Flight flight = dispatch(next.runCaseId(), run.env(), kafka);
                    if (flight == null) {
                        gate.release();
                    } else {
                        inFlight.put(next.runCaseId(), flight);
                        if (next.serialKey() != null) {
                            activeKeys.add(next.serialKey());
                        }
                        consecutiveFailures = 0;
                    }
                } catch (PublishFailure e) {
                    gate.release();
                    finish(next.runCaseId(), "ERROR", truncate(e.getMessage()));
                    if (++consecutiveFailures >= publishFailureLimit) {
                        aborted = true;
                        String why = "Run aborted: publishing failed " + consecutiveFailures + " times in a row (" + e.getMessage() + ")";
                        pending.forEach(p -> finish(p.runCaseId(), "SKIPPED", truncate(why)));
                        pending.clear();
                    }
                }
            }

            now = System.nanoTime();
            if (!inFlight.isEmpty() && now - lastPoll >= pollNanos) {
                lastPoll = now;
                poll(source, inFlight, activeKeys, finishing);
            }
            if (now - lastCounters >= 2_000_000_000L) {
                lastCounters = now;
                updateCounters(run.id());
            }
            finishing.removeIf(Future::isDone);
            if (inFlight.isEmpty() && pending.isEmpty()) {
                break;
            }
            sleep(tickMs);
        }

        if (cancelled) {
            String why = "Run cancelled";
            pending.forEach(p -> finish(p.runCaseId(), "SKIPPED", why));
            inFlight.values().forEach(f -> {
                finish(f.def().runCaseId(), "SKIPPED", why + " while waiting for NGC");
                gate.release();
            });
        }
        finishing.forEach(f -> {
            try {
                f.get();
            } catch (Exception e) {
                log.error("Finishing an event failed", e);
            }
        });
        complete(run.id(), cancelled, aborted);
    }

    /** First pending event whose policy has nothing else running; leaves the others queued. */
    private static Pending pickNext(Deque<Pending> pending, Set<String> activeKeys) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.serialKey() == null || !activeKeys.contains(p.serialKey())) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    /** Events published before a restart keep waiting until their own deadline. */
    private void resumePublished(long runId, Map<Long, Flight> inFlight, Set<String> activeKeys) {
        for (long id : jdbc.sql("SELECT id FROM test_run_case WHERE run_id = :r AND status = 'RUNNING' ORDER BY id")
                .param("r", runId).query(Long.class).list()) {
            Instant publishedAt = jdbc.sql("SELECT published_at FROM test_run_case WHERE id = :i AND published_at IS NOT NULL")
                    .param("i", id).query((rs, n) -> rs.getTimestamp(1).toInstant()).optional().orElse(null);
            if (publishedAt == null) {
                finish(id, "ERROR", "Interrupted before the publish was confirmed; re-run the failed events");
                continue;
            }
            try {
                Def d = loadDef(id);
                inFlight.put(id, new Flight(d, json.readTree(d.expected()), publishedAt.plusSeconds(d.timeout())));
                if (d.serialKey() != null) {
                    activeKeys.add(d.serialKey());
                }
                gate.force();
            } catch (Exception e) {
                finish(id, "ERROR", truncate("Could not resume: " + e.getMessage()));
            }
        }
    }

    private void poll(NgcDataSource source, Map<Long, Flight> inFlight, Set<String> activeKeys, List<Future<?>> finishing) {
        Map<String, NgcView> views;
        try {
            views = source.collectBatch(inFlight.values().stream().map(f -> f.def().correlationId()).toList());
        } catch (RuntimeException e) {
            log.warn("Could not read NGC this cycle ({}); will retry until each event's deadline", e.getMessage());
            views = Map.of();
        }
        Instant now = Instant.now();
        for (Iterator<Flight> it = inFlight.values().iterator(); it.hasNext(); ) {
            Flight f = it.next();
            Def d = f.def();
            NgcView view = views.get(d.correlationId());
            if (view != null) {
                recorder.record(d.runCaseId(), d.correlationId(), view);
            }
            boolean settled = view != null && assertions.settled(f.expected(), view);
            if (settled || now.isAfter(f.deadline())) {
                it.remove();
                if (d.serialKey() != null) {
                    activeKeys.remove(d.serialKey());
                }
                gate.release();
                NgcView last = view != null ? view : new NgcView(d.correlationId(), List.of(), 0, 0, 0, List.of());
                finishing.add(finishPool.submit(() -> finishCase(f, last, settled)));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ one event

    private static final class PublishFailure extends RuntimeException {
        PublishFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** @return the event now in flight, or null if it was finished as an error without being published */
    private Flight dispatch(long runCaseId, String env, EventPublisher kafka) {
        Def d;
        try {
            d = loadDef(runCaseId);
        } catch (RuntimeException e) {
            finish(runCaseId, "ERROR", truncate(e.getMessage()));
            return null;
        }
        try {
            if (!allowedCarriers.contains(d.carrier())) {
                throw new IllegalStateException("Safety guard: carrier " + d.carrier() + " is not in regression.allowed-carriers");
            }
            String topic = jdbc.sql("""
                    SELECT t.topic_name FROM kafka_topic t JOIN environment e ON e.id = t.environment_id
                    WHERE e.code = :env AND t.kind = :k AND t.active = TRUE AND t.topic_name = REPLACE(:p, '{env}', e.code)
                    """).param("env", env).param("k", d.kind()).param("p", d.pattern()).query(String.class).optional()
                    .orElseThrow(() -> new IllegalStateException("No " + d.kind() + " topic '" + d.pattern() + "' in " + env));

            String payloadText = artifacts.findContent(d.payloadArtifact());
            if (d.planCode() != null) {
                payloadText = planRewriter.apply(payloadText, d.planCode());
            }
            String injected = rewriter.rewrite(payloadText, d.originalId(), d.correlationId());
            PayloadInfo info = payloads.validate(injected);
            if (!info.valid() || !d.correlationId().equals(info.correlationId())) {
                throw new IllegalStateException("Injected payload failed validation: " + info.errors());
            }
            long injectedArtifact = artifacts.saveInDb("EVENT_PAYLOAD", "injected-" + d.correlationId() + ".json",
                    "application/json", injected, "runner");
            jdbc.sql("""
                    UPDATE test_run_case SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP, topic_name = :t,
                      source_correlation_id = :o, injected_payload_artifact_id = :a WHERE id = :i
                    """).param("t", topic).param("o", d.originalId()).param("a", injectedArtifact).param("i", runCaseId)
                    .update();

            EventPublisher.PublishResult sent;
            try {
                sent = kafka.publish(topic, d.correlationId(), injected);
            } catch (RuntimeException e) {
                throw new PublishFailure(e.getMessage(), e);
            }
            jdbc.sql("UPDATE test_run_case SET published_at = CURRENT_TIMESTAMP, kafka_partition = :p, kafka_offset = :o WHERE id = :i")
                    .param("p", sent.partition()).param("o", sent.offset()).param("i", runCaseId).update();
            jdbc.sql("""
                    INSERT INTO run_observation (run_case_id, occurred_at, source, collection, event_type, status,
                      correlation_id, source_document_id)
                    VALUES (:i, CURRENT_TIMESTAMP, 'KAFKA', 'KAFKA', 'published', 'ACKED', :c, :d)
                    """).param("i", runCaseId).param("c", d.correlationId())
                    .param("d", topic + "@" + sent.partition() + ":" + sent.offset()).update();
            return new Flight(d, json.readTree(d.expected()), Instant.now().plusSeconds(d.timeout()));
        } catch (PublishFailure e) {
            throw e;
        } catch (Exception e) {
            log.error("Event {} could not be sent", d.correlationId(), e);
            finish(runCaseId, "ERROR", truncate(e.getMessage()));
            return null;
        }
    }

    private Def loadDef(long runCaseId) {
        return jdbc.sql("""
                SELECT rc.correlation_id, rc.serial_key, v.payload_artifact_id, v.kafka_kind, v.kafka_topic_pattern,
                       v.carrier, v.expected_json, v.timeout_seconds, v.payload_correlation_id, v.golden_artifact_id,
                       rc.plan_code
                FROM test_run_case rc JOIN test_case_version v ON v.id = rc.test_case_version_id WHERE rc.id = :i
                """).param("i", runCaseId).query((rs, n) -> new Def(runCaseId, rs.getString(1), rs.getString(2),
                rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                rs.getString(9), rs.getObject(10) == null ? null : rs.getLong(10), rs.getString(11))).single();
    }

    /** Assert, check XML, store the outcome. Runs off the dispatch thread so slow S3 reads never hold up publishing. */
    private void finishCase(Flight f, NgcView view, boolean settled) {
        Def d = f.def();
        finishers.acquireUninterruptibly();
        try {
            List<AssertionOutcome> outcomes = new ArrayList<>(assertions.evaluate(f.expected(), view));
            if (f.expected().has("xml")) {
                outcomes.addAll(verifyXml(d.runCaseId(), d.correlationId(), f.expected().get("xml"), view, d.goldenArtifact()));
            }
            for (AssertionOutcome o : outcomes) {
                jdbc.sql("""
                        INSERT INTO assertion_result (run_case_id, category, path, expected, actual, passed, message)
                        VALUES (:i, :c, :p, :e, :a, :ok, :m)
                        """).param("i", d.runCaseId()).param("c", o.category()).param("p", o.path())
                        .param("e", truncate(o.expected())).param("a", truncate(o.actual())).param("ok", o.passed())
                        .param("m", truncate(o.message())).update();
            }
            List<String> failed = outcomes.stream().filter(o -> !o.passed()).map(AssertionOutcome::path).toList();
            if (failed.isEmpty()) {
                finish(d.runCaseId(), "PASSED", null);
            } else {
                String summary = failed.size() + " assertion(s) failed: " + String.join(", ", failed);
                finish(d.runCaseId(), settled ? "FAILED" : "TIMEOUT",
                        truncate(settled ? summary : "Timed out after " + d.timeout() + "s; " + summary));
            }
        } catch (RuntimeException e) {
            log.error("Event {} errored while being checked", d.correlationId(), e);
            finish(d.runCaseId(), "ERROR", truncate(e.getMessage()));
        } finally {
            finishers.release();
        }
    }

    /** Fetches the generated XML from S3, checks it, and keeps a copy with the run for later inspection. */
    private List<AssertionOutcome> verifyXml(long runCaseId, String correlationId, JsonNode xmlExpected, NgcView view,
                                             Long goldenArtifact) {
        XmlStore store = xmlStore.getIfAvailable();
        if (store == null) {
            return List.of(new AssertionOutcome("XML", "xml", "XML checks", "not evaluated", false,
                    "S3 is not configured (S3_BUCKET_NAME and AWS credentials)"));
        }
        String golden = goldenArtifact == null ? null : artifacts.findContent(goldenArtifact);
        XmlVerifier.Result result = xmlVerifier.verify(xmlExpected, view, golden, store);
        if (result.xml() != null) {
            long artifact = artifacts.saveInDb("RUN_XML", correlationId + "-" + result.role() + ".xml",
                    "application/xml", result.xml(), "runner");
            jdbc.sql("INSERT INTO run_case_artifact (run_case_id, artifact_id, role) VALUES (:r, :a, 'GENERATED_XML')")
                    .param("r", runCaseId).param("a", artifact).update();
        }
        return result.outcomes();
    }

    // ------------------------------------------------------------------------------------------------ bookkeeping

    private boolean cancelRequested(long runId) {
        return jdbc.sql("SELECT cancel_requested FROM test_run WHERE id = :r").param("r", runId).query(Boolean.class).single();
    }

    private void skipOrFail(long runId, String fromStatus, String toStatus, String message) {
        jdbc.sql("""
                UPDATE test_run_case SET status = :to, failure_summary = :m, finished_at = CURRENT_TIMESTAMP
                WHERE run_id = :r AND status = :from
                """).param("to", toStatus).param("m", message).param("r", runId).param("from", fromStatus).update();
    }

    private void updateCounters(long runId) {
        jdbc.sql("""
                UPDATE test_run SET passed_cases = (SELECT COUNT(*) FROM test_run_case WHERE run_id = :r AND status = 'PASSED'),
                  failed_cases = (SELECT COUNT(*) FROM test_run_case WHERE run_id = :r AND status IN ('FAILED','TIMEOUT','ERROR'))
                WHERE id = :r
                """).param("r", runId).update();
    }

    private void complete(long runId, boolean cancelled, boolean aborted) {
        updateCounters(runId);
        int errors = count(runId, "ERROR");
        int bad = errors + count(runId, "TIMEOUT") + count(runId, "FAILED");
        String status = cancelled ? "CANCELLED" : aborted || errors > 0 ? "ERROR" : bad > 0 ? "FAILED" : "PASSED";
        jdbc.sql("UPDATE test_run SET status = :s, finished_at = CURRENT_TIMESTAMP WHERE id = :r")
                .param("s", status).param("r", runId).update();
    }

    private void finish(long runCaseId, String status, String summary) {
        jdbc.sql("""
                UPDATE test_run_case SET status = :s, failure_summary = :m, finished_at = CURRENT_TIMESTAMP WHERE id = :i
                """).param("s", status).param("m", summary).param("i", runCaseId).update();
    }

    private int count(long runId, String status) {
        return jdbc.sql("SELECT COUNT(*) FROM test_run_case WHERE run_id = :r AND status = :s").param("r", runId)
                .param("s", status).query(Integer.class).single();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Run interrupted", e);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1900 ? s.substring(0, 1900) : s;
    }
}
