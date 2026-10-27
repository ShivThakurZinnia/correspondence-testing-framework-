package com.zinnia.regression.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.collector.NgcDataSource;
import com.zinnia.regression.collector.NgcView;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import com.zinnia.regression.collector.NgcView.TargetView;
import com.zinnia.regression.publisher.EventPublisher;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import com.zinnia.regression.testcase.ValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:bulk-test;DB_CLOSE_DELAY=-1",
        "regression.poll-interval-ms=20", "regression.allowed-carriers=GILI", "regression.run.events-per-second=1000",
        "regression.run.large-run-threshold=100000", "regression.run.publish-failure-limit=3",
        "regression.run.max-in-flight=25", "regression.run.global-max-in-flight=100"})
class BulkRunTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- the fake world: Kafka records what is sent, NGC settles a case after N polls ---------------------------
    static final List<String[]> SENT = new CopyOnWriteArrayList<>();            // {correlationId, policy, millis}
    static final Map<String, String> POLICY_OF = new ConcurrentHashMap<>();     // correlationId -> policy
    static final Map<String, AtomicInteger> POLLS = new ConcurrentHashMap<>();
    static final Set<String> ACTIVE_POLICIES = ConcurrentHashMap.newKeySet();
    static final Set<String> SETTLED = ConcurrentHashMap.newKeySet();
    static final Set<String> FAIL_POLICIES = ConcurrentHashMap.newKeySet();    // these end with the wrong status
    static final List<Integer> BATCH_SIZES = new CopyOnWriteArrayList<>();
    static final AtomicInteger SINGLE_COLLECTS = new AtomicInteger();
    static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();
    static final AtomicInteger POLICY_OVERLAPS = new AtomicInteger();
    static volatile int settleAfterPolls = 1;
    static volatile boolean neverSettle = false;
    static volatile boolean kafkaDown = false;

    @TestConfiguration
    static class Fakes {
        @Bean
        EventPublisher publisher() {
            return (topic, key, value) -> {
                if (kafkaDown) {
                    throw new IllegalStateException("broker unavailable");
                }
                try {
                    String policy = null;
                    for (JsonNode i : MAPPER.readTree(value).get("identifiers")) {
                        if (i.get("identifier").asText().equals("policyNumber")) {
                            policy = i.get("value").asText();
                        }
                    }
                    POLICY_OF.put(key, policy);
                    if (policy != null && !ACTIVE_POLICIES.add(policy)) {
                        POLICY_OVERLAPS.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                MAX_IN_FLIGHT.accumulateAndGet(IN_FLIGHT.incrementAndGet(), Math::max);
                SENT.add(new String[]{key, POLICY_OF.get(key), String.valueOf(System.currentTimeMillis())});
                return new EventPublisher.PublishResult(0, SENT.size());
            };
        }

        @Bean
        NgcDataSource ngc() {
            return new NgcDataSource() {
                @Override
                public NgcView collect(String id) {
                    SINGLE_COLLECTS.incrementAndGet();
                    return view(id);
                }

                @Override
                public Map<String, NgcView> collectBatch(Collection<String> ids) {
                    BATCH_SIZES.add(ids.size());
                    Map<String, NgcView> out = new java.util.LinkedHashMap<>();
                    ids.forEach(id -> out.put(id, view(id)));
                    return out;
                }

                @Override
                public boolean incomingEventExists(String correlationId) {
                    return false;
                }
            };
        }
    }

    static NgcView view(String id) {
        int polls = POLLS.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        boolean settled = !neverSettle && polls >= settleAfterPolls;
        String policy = POLICY_OF.get(id);
        if (settled && SETTLED.add(id)) {
            IN_FLIGHT.decrementAndGet();
            if (policy != null) {
                ACTIVE_POLICIES.remove(policy);
            }
        }
        String status = !settled ? "PROCESSING" : policy != null && FAIL_POLICIES.contains(policy) ? "FAILED" : "SENT_TO_CDS";
        var target = new TargetView("OWNER", status, "PRINT", null, null, "POLPG", "x.xml", null, null);
        return new NgcView(id, List.of(new IncomingEventView("ngc-" + id, status, null, "base", List.of(target))), 1, 0, 0, List.of());
    }

    static final String EXPECTED = "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}";

    @Autowired RunService runs;
    @Autowired RunExecutor executor;
    @Autowired TestCaseService cases;
    @Autowired ArtifactRepository artifacts;
    @Autowired RunQueryController queries;
    @Autowired RetentionJob retention;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        for (String t : List.of("run_observation", "assertion_result", "run_case_artifact", "test_run_case", "test_run",
                "module_version_case", "module_version", "test_case_version_tag", "test_case_version_plan",
                "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        SENT.clear();
        POLICY_OF.clear();
        POLLS.clear();
        ACTIVE_POLICIES.clear();
        SETTLED.clear();
        FAIL_POLICIES.clear();
        BATCH_SIZES.clear();
        SINGLE_COLLECTS.set(0);
        IN_FLIGHT.set(0);
        MAX_IN_FLIGHT.set(0);
        POLICY_OVERLAPS.set(0);
        settleAfterPolls = 1;
        neverSettle = false;
        kafkaDown = false;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------
    long payload(String policy) {
        String json = """
                {"id":"a","source":"zahara","specversion":"1","type":"policy.activation.completed",
                 "correlationid":"11111111-2222-4333-8444-555555555555","carrier":"GILI",
                 "identifiers":[{"identifier":"planCode","value":"GCFIA902"},{"identifier":"policyNumber","value":"%s"}]}
                """.formatted(policy);
        return artifacts.saveInDb("EVENT_PAYLOAD", policy + ".json", "application/json", json, "t");
    }

    /** One test case per policy; a policy repeated in the list gives several cases for the same policy. */
    void caseFor(String key, String policy, int timeoutSeconds) {
        cases.save("policy-page", key, new TestCaseRequest(key, null, "SOR", "zinnia.correspondence.sor",
                "policy.activation.completed", "GILI", null, payload(policy), null, EXPECTED, timeoutSeconds, true,
                List.of("client:GILI"), "n"), "t");
    }

    void manyCases(int n) {
        for (int i = 0; i < n; i++) {
            caseFor("bulk-case-" + String.format("%02d", i), "POL" + i, 600);
        }
    }

    RunRequest request(Integer maxInFlight, Double eps) {
        return new RunRequest("policy-page", null, "qa", null, "cli", maxInFlight, eps, null);
    }

    String status(String key) {
        return jdbc.sql("SELECT status FROM test_run WHERE run_key = :k").param("k", key).query(String.class).single();
    }

    int cases(String key, String status) {
        return jdbc.sql("SELECT COUNT(*) FROM test_run_case rc JOIN test_run r ON r.id = rc.run_id WHERE r.run_key = :k AND rc.status = :s")
                .param("k", key).param("s", status).query(Integer.class).single();
    }

    // ---- throttling, rate, ordering, batching -----------------------------------------------------------------
    @Test
    void neverHasMoreUnfinishedEventsThanMaxInFlightAndStillFinishesEverything() {
        manyCases(10);
        settleAfterPolls = 3;

        String run = runs.create(request(3, null), "t");
        executor.execute(run);

        assertThat(status(run)).isEqualTo("PASSED");
        assertThat(cases(run, "PASSED")).isEqualTo(10);
        assertThat(SENT).hasSize(10);
        assertThat(MAX_IN_FLIGHT.get()).as("never more than 3 unfinished at once").isLessThanOrEqualTo(3).isGreaterThanOrEqualTo(2);
    }

    @Test
    void publishesNoFasterThanTheConfiguredRate() {
        manyCases(5);

        String run = runs.create(request(25, 20.0), "t");
        executor.execute(run);

        long first = Long.parseLong(SENT.get(0)[2]);
        long last = Long.parseLong(SENT.get(4)[2]);
        assertThat(last - first).as("5 events at 20/s need at least 4 gaps of 50 ms").isGreaterThanOrEqualTo(170);
        assertThat(status(run)).isEqualTo("PASSED");
    }

    @Test
    void neverRunsTwoEventsOfTheSamePolicyAtTheSameTime() {
        for (int i = 0; i < 4; i++) {
            caseFor("same-policy-" + i, "POLSAME", 600);
        }
        caseFor("other-policy", "POLOTHER", 600);
        settleAfterPolls = 3;

        String run = runs.create(request(10, null), "t");
        executor.execute(run);

        assertThat(status(run)).isEqualTo("PASSED");
        assertThat(SENT).hasSize(5);
        assertThat(POLICY_OVERLAPS.get()).as("same policy was never in flight twice").isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM test_run_case WHERE serial_key = 'POLSAME|GCFIA902'").query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void planCodeVariantsOfOnePolicyRunSideBySideButTheSamePolicyAndPlanNeverDo() {
        cases.save("policy-page", "variants", new TestCaseRequest("variants", null, "SOR", "zinnia.correspondence.sor",
                "policy.activation.completed", "GILI", List.of("GCFIA901", "GCFIA902", "GCFIA903"), payload("POLVAR"), null, EXPECTED, 600, true,
                List.of("client:GILI"), "n"), "t");
        settleAfterPolls = 4;

        String run = runs.create(request(10, null), "t");
        executor.execute(run);

        assertThat(status(run)).isEqualTo("PASSED");
        assertThat(MAX_IN_FLIGHT.get()).as("3 plan codes of one policy overlapped").isEqualTo(3);
        assertThat(POLICY_OVERLAPS.get()).as("fake counts only same-policy overlaps; variants share a policy number").isEqualTo(2);
        assertThat(jdbc.sql("SELECT serial_key FROM test_run_case ORDER BY id").query(String.class).list())
                .containsExactly("POLVAR|GCFIA901", "POLVAR|GCFIA902", "POLVAR|GCFIA903");
    }

    @Test
    void watchesAllInFlightEventsWithBatchedQueriesNeverOneByOne() {
        manyCases(8);
        settleAfterPolls = 4;

        String run = runs.create(request(8, null), "t");
        executor.execute(run);

        assertThat(status(run)).isEqualTo("PASSED");
        assertThat(SINGLE_COLLECTS.get()).isZero();
        assertThat(BATCH_SIZES.stream().mapToInt(Integer::intValue).max().orElse(0)).as("several events share one query").isGreaterThanOrEqualTo(3);
        int polledEvents = BATCH_SIZES.stream().mapToInt(Integer::intValue).sum();
        assertThat(BATCH_SIZES.size()).as("far fewer queries than event-polls").isLessThan(polledEvents / 2);
    }

    // ---- safety valves -----------------------------------------------------------------------------------------
    @Test
    void cancelStopsPublishingAndSkipsWhatIsLeft() throws Exception {
        manyCases(6);
        neverSettle = true;
        String run = runs.create(request(2, null), "t");

        var future = executor.executeAsync(run);
        long end = System.currentTimeMillis() + 5000;
        while (SENT.size() < 2 && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        runs.cancel(run);
        future.get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(status(run)).isEqualTo("CANCELLED");
        assertThat(SENT).as("only the two allowed in flight were ever sent").hasSize(2);
        assertThat(cases(run, "SKIPPED")).isEqualTo(6);
        assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case WHERE status = 'SKIPPED' ORDER BY id LIMIT 1")
                .query(String.class).single()).startsWith("Run cancelled");
        assertThatThrownBy(() -> runs.cancel(run)).isInstanceOf(ValidationException.class);
    }

    @Test
    void repeatedPublishFailuresAbortTheRunInsteadOfErroringEveryEvent() {
        manyCases(8);
        kafkaDown = true;

        String run = runs.create(request(25, null), "t");
        executor.execute(run);

        assertThat(status(run)).isEqualTo("ERROR");
        assertThat(cases(run, "ERROR")).as("only the 3 attempts errored").isEqualTo(3);
        assertThat(cases(run, "SKIPPED")).isEqualTo(5);
        assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case WHERE status = 'SKIPPED' LIMIT 1").query(String.class).single())
                .contains("Run aborted").contains("3 times in a row").contains("broker unavailable");
        assertThat(SENT).isEmpty();
    }

    @Test
    void oneFailedPublishDoesNotStopTheRunWhenKafkaRecovers() {
        manyCases(4);
        String run = runs.create(request(25, null), "t");
        kafkaDown = true;
        var first = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
            }
            kafkaDown = false;
        });
        first.start();

        executor.execute(run);

        assertThat(SENT.size() + cases(run, "ERROR")).isEqualTo(4);
        assertThat(cases(run, "SKIPPED")).isZero();
    }

    // ---- restart safety -----------------------------------------------------------------------------------------
    @Test
    void anUnfinishedRunResumesAfterARestartWithoutRepublishing() {
        manyCases(5);
        String run = runs.create(request(25, null), "t");
        List<Long> ids = jdbc.sql("SELECT rc.id FROM test_run_case rc JOIN test_run r ON r.id = rc.run_id WHERE r.run_key = :k ORDER BY rc.id")
                .param("k", run).query(Long.class).list();
        // simulate the state a crash leaves behind: 2 published and waiting, 1 mid-publish, 2 never started
        jdbc.sql("UPDATE test_run SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP WHERE run_key = :k").param("k", run).update();
        for (int i = 0; i < 2; i++) {
            jdbc.sql("UPDATE test_run_case SET status = 'RUNNING', published_at = CURRENT_TIMESTAMP, topic_name = 'zinnia.correspondence.sor' WHERE id = :i")
                    .param("i", ids.get(i)).update();
            POLICY_OF.put(jdbc.sql("SELECT correlation_id FROM test_run_case WHERE id = :i").param("i", ids.get(i)).query(String.class).single(), "P" + i);
            IN_FLIGHT.incrementAndGet();
        }
        jdbc.sql("UPDATE test_run_case SET status = 'RUNNING' WHERE id = :i").param("i", ids.get(2)).update();

        executor.execute(run);

        assertThat(SENT).as("the two already published were not sent again; only the 2 untouched ones").hasSize(2);
        assertThat(cases(run, "PASSED")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case WHERE id = :i").param("i", ids.get(2)).query(String.class).single())
                .contains("Interrupted before the publish was confirmed");
        assertThat(status(run)).isEqualTo("ERROR");
    }

    @Test
    void aResumedEventKeepsItsOriginalDeadlineSoOldOnesTimeOutAtOnce() {
        caseFor("old-event", "POLOLD", 60);
        String run = runs.create(request(25, null), "t");
        jdbc.sql("UPDATE test_run SET status = 'RUNNING' WHERE run_key = :k").param("k", run).update();
        jdbc.sql("UPDATE test_run_case SET status = 'RUNNING', published_at = TIMESTAMPADD(HOUR, -2, CURRENT_TIMESTAMP)").update();
        neverSettle = true;

        long start = System.currentTimeMillis();
        executor.execute(run);

        assertThat(System.currentTimeMillis() - start).isLessThan(3000);
        assertThat(cases(run, "TIMEOUT")).isEqualTo(1);
        assertThat(status(run)).isEqualTo("FAILED");
    }

    // ---- re-run ------------------------------------------------------------------------------------------------
    @Test
    void rerunFailedCreatesANewRunWithOnlyTheEventsThatDidNotPass() {
        manyCases(4);
        FAIL_POLICIES.add("POL1");
        FAIL_POLICIES.add("POL3");
        String first = runs.create(request(null, null), "t");
        executor.execute(first);
        assertThat(cases(first, "FAILED")).isEqualTo(2);
        FAIL_POLICIES.clear();
        SENT.clear();

        String second = runs.rerunFailed(first, "t");
        executor.execute(second);

        assertThat(SENT).hasSize(2);
        assertThat(status(second)).isEqualTo("PASSED");
        assertThat(jdbc.sql("SELECT total_cases FROM test_run WHERE run_key = :k").param("k", second).query(Integer.class).single()).isEqualTo(2);
        assertThat(queries.run(second, null, null, 0, 50).get("rerun_of")).isEqualTo(first);
        assertThatThrownBy(() -> runs.rerunFailed(second, "t")).isInstanceOf(ValidationException.class);
    }

    // ---- reading a big run ------------------------------------------------------------------------------------
    @Test
    void runDetailGivesCountersFiltersAndPagesWithFailuresFirst() {
        manyCases(8);
        FAIL_POLICIES.addAll(Set.of("POL2", "POL5"));
        String run = runs.create(request(null, null), "t");
        executor.execute(run);

        Map<String, Object> all = queries.run(run, null, null, 0, 3);
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) all.get("summary");
        assertThat(summary).containsEntry("PASSED", 6L).containsEntry("FAILED", 2L);
        assertThat(all.get("matching")).isEqualTo(8L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> page = (List<Map<String, Object>>) all.get("cases");
        assertThat(page).hasSize(3);
        assertThat(page.subList(0, 2)).extracting(c -> c.get("status")).containsOnly("FAILED");
        assertThat(page.get(2).get("status")).isEqualTo("PASSED");

        @SuppressWarnings("unchecked") List<Map<String, Object>> failed = (List<Map<String, Object>>) queries.run(run, "FAILED,ERROR", null, 0, 50).get("cases");
        assertThat(failed).hasSize(2);
        assertThat(queries.run(run, null, "bulk-case-05", 0, 50).get("matching")).isEqualTo(1L);
        assertThat(queries.run(run, "TIMEOUT", null, 0, 50).get("matching")).isEqualTo(0L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> second = (List<Map<String, Object>>) queries.run(run, null, null, 1, 3).get("cases");
        assertThat(second).hasSize(3);
        assertThat(second).extracting(c -> c.get("run_case_id")).doesNotContainAnyElementsOf(page.stream().map(c -> c.get("run_case_id")).toList());
    }

    @Test
    void compareSaysWhatBrokeWhatWasFixedAndWhatIsNew() {
        caseFor("case-a", "POLA", 600);
        caseFor("case-b", "POLB", 600);
        caseFor("case-c", "POLC", 600);
        FAIL_POLICIES.add("POLC");
        String before = runs.create(request(null, null), "t");
        executor.execute(before);
        assertThat(queries.compare(before).get("previous")).as("first run has nothing to compare with").isNull();

        FAIL_POLICIES.clear();
        FAIL_POLICIES.add("POLA");
        caseFor("case-d", "POLD", 600);
        String now = runs.create(request(null, null), "t");
        executor.execute(now);

        Map<String, Object> diff = queries.compare(now);
        assertThat(((Map<?, ?>) diff.get("previous")).get("run_key")).isEqualTo(before);
        assertThat(diff.get("new_failures")).isEqualTo(1L);
        assertThat(diff.get("fixed")).isEqualTo(1L);
        assertThat(diff.get("still_failing")).isEqualTo(0L);
        assertThat(diff.get("new_cases")).isEqualTo(1L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> broke = (List<Map<String, Object>>) diff.get("new_failure_events");
        assertThat(broke).singleElement().satisfies(e -> {
            assertThat(e.get("scenario_key")).isEqualTo("case-a");
            assertThat(e.get("previous_status")).isEqualTo("PASSED");
        });
    }

    // ---- housekeeping -----------------------------------------------------------------------------------------
    @Test
    void retentionDropsOldDetailThenOldRunsButNeverTouchesActiveOnes() {
        manyCases(2);
        String old = runs.create(request(null, null), "t");
        executor.execute(old);
        String recent = runs.create(request(null, null), "t");
        executor.execute(recent);
        String active = runs.create(request(null, null), "t");
        jdbc.sql("UPDATE test_run SET status = 'RUNNING' WHERE run_key = :k").param("k", active).update();
        jdbc.sql("UPDATE test_run SET finished_at = TIMESTAMPADD(DAY, -40, CURRENT_TIMESTAMP) WHERE run_key = :k").param("k", old).update();
        jdbc.sql("UPDATE test_run SET finished_at = TIMESTAMPADD(DAY, -40, CURRENT_TIMESTAMP) WHERE run_key = :k").param("k", active).update();
        int oldObs = count("run_observation", old);
        assertThat(oldObs).isGreaterThan(0);

        var r = retention.purge(java.time.OffsetDateTime.now());

        assertThat(r.runsPurged()).isEqualTo(1);
        assertThat(count("run_observation", old)).isZero();
        assertThat(count("assertion_result", old)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM test_run_case rc JOIN test_run r ON r.id = rc.run_id WHERE r.run_key = :k AND rc.injected_payload_artifact_id IS NOT NULL")
                .param("k", old).query(Integer.class).single()).isZero();
        assertThat(cases(old, "PASSED")).as("per-event results are kept").isEqualTo(2);
        assertThat(count("run_observation", recent)).isGreaterThan(0);
        assertThat(count("run_observation", active)).isZero();
        assertThat(status(active)).isEqualTo("RUNNING");

        jdbc.sql("UPDATE test_run SET finished_at = TIMESTAMPADD(DAY, -400, CURRENT_TIMESTAMP) WHERE run_key = :k").param("k", old).update();
        assertThat(retention.purge(java.time.OffsetDateTime.now()).runsDeleted()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM test_run WHERE run_key = :k").param("k", old).query(Integer.class).single()).isZero();
        assertThat(status(recent)).isEqualTo("PASSED");
    }

    private int count(String table, String runKey) {
        String col = table.equals("run_observation") || table.equals("assertion_result") ? "run_case_id" : "id";
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " t WHERE t." + col + " IN (SELECT rc.id FROM test_run_case rc JOIN test_run r ON r.id = rc.run_id WHERE r.run_key = :k)")
                .param("k", runKey).query(Integer.class).single();
    }

    @SuppressWarnings("unused")
    private List<String> unused() {
        return new ArrayList<>();
    }
}
