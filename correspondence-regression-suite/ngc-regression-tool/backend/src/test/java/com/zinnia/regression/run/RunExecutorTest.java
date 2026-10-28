package com.zinnia.regression.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.collector.NgcDataSource;
import com.zinnia.regression.collector.NgcView;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import com.zinnia.regression.collector.NgcView.TargetView;
import com.zinnia.regression.collector.Observation;
import com.zinnia.regression.payload.PayloadTest;
import com.zinnia.regression.publisher.EventPublisher;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import com.zinnia.regression.testcase.ValidationException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:exec-test;DB_CLOSE_DELAY=-1",
        "regression.poll-interval-ms=10", "regression.allowed-carriers=GILI", "regression.run.events-per-second=1000"})
class RunExecutorTest {

    record Sent(String topic, String key, String value) {
    }

    static final List<Sent> SENT = new CopyOnWriteArrayList<>();
    static final ConcurrentHashMap<String, Function<String, NgcView>> NGC = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> S3 = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Fakes {
        @Bean
        EventPublisher publisher() {
            return (topic, key, value) -> {
                SENT.add(new Sent(topic, key, value));
                return new EventPublisher.PublishResult(0, SENT.size());
            };
        }

        @Bean
        com.zinnia.regression.xml.XmlStore xmlStore() {
            return new com.zinnia.regression.xml.XmlStore() {
                @Override
                public String defaultBucket() {
                    return "b";
                }

                @Override
                public java.util.Optional<String> read(String bucket, String key) {
                    return java.util.Optional.ofNullable(S3.get(bucket + "/" + key));
                }
            };
        }

        @Bean
        NgcDataSource ngc() {
            return new NgcDataSource() {
                @Override
                public NgcView collect(String correlationId) {
                    return NGC.get("current").apply(correlationId);
                }

                @Override
                public boolean incomingEventExists(String correlationId) {
                    return false;
                }
            };
        }
    }

    static final String EXPECTED = """
            {"incomingEvent":{"status":"SENT_TO_CDS"},
             "targets":[{"role":"OWNER","status":"SENT_TO_CDS","deliveryType":"PRINT","documentType":"POLPG"}],
             "cds":{"published":true,"count":1},"journey":["requestValid","requestSentToCDS"],
             "noFailures":["technical_failures"]}""";

    @Autowired RunService runs;
    @Autowired RunExecutor executor;
    @Autowired TestCaseService cases;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    long payload;

    @BeforeEach
    void clean() {
        SENT.clear();
        for (String t : List.of("run_observation", "assertion_result", "run_case_artifact", "test_run_case", "test_run",
                "module_version_case", "module_version", "test_case_version_tag", "test_case_version_plan", "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        S3.clear();
        payload = artifacts.saveInDb("EVENT_PAYLOAD", "p.json", "application/json", PayloadTest.PAYLOAD, "t");
    }

    void scenario(String key, String carrier, List<String> tags) {
        scenario(key, carrier, tags, EXPECTED, null);
    }

    void scenario(String key, String carrier, List<String> tags, String expected, Long golden) {
        cases.save("policy-page", key, new TestCaseRequest(key, null, "SOR", "zinnia.correspondence.sor",
                "policy.activation.completed", carrier, List.of("GCFIA903"), payload, golden, expected, 600, true, tags, "n"), "t");
    }

    NgcView chain(String correlationId, String status, String delivery) {
        var target = new TargetView("OWNER", "SENT_TO_CDS", delivery, "YES", "PRIMARYSERVICINGAGENT", "POLPG", "x.XML",
                null, null);
        var incoming = new IncomingEventView("ngc-" + correlationId, status, null, "base", List.of(target));
        OffsetDateTime now = OffsetDateTime.now();
        return new NgcView(correlationId, List.of(incoming), 1, 0, 0, List.of(
                new Observation(now, "MONGO", "incoming_events", "policy.activation.completed", status, null,
                        "ngc-" + correlationId, null, null, null, "doc1", null),
                new Observation(now, "MONGO", "event_journey", "correspondence.generation.requestValid", null, null,
                        null, null, null, null, "j1", null),
                new Observation(now, "MONGO", "event_journey", "correspondence.generation.requestSentToCDS", null,
                        "OWNER", null, null, null, null, "j2", null)));
    }

    String runAll(String tags) {
        String key = runs.create(new RunRequest("policy-page", null, "qa", tags, "cli"), "tester");
        executor.execute(key);
        return key;
    }

    String status(String runKey) {
        return jdbc.sql("SELECT status FROM test_run WHERE run_key = :k").param("k", runKey).query(String.class).single();
    }

    @Test
    void happyRunInjectsFreshCorrelationIdAndPasses() {
        scenario("gili-owner-print", "GILI", List.of("client:GILI", "event:policy-page"));
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        String run = runAll("client:GILI");

        assertThat(status(run)).isEqualTo("PASSED");
        assertThat(SENT).hasSize(1);
        Sent sent = SENT.get(0);
        assertThat(sent.topic()).isEqualTo("zinnia.correspondence.sor");
        assertThat(sent.value()).doesNotContainIgnoringCase(PayloadTest.ORIGINAL).contains(sent.key());
        assertThat(jdbc.sql("SELECT correlation_id FROM test_run_case").query(String.class).single()).isEqualTo(sent.key());
        assertThat(jdbc.sql("SELECT source_correlation_id FROM test_run_case").query(String.class).single())
                .isEqualTo(PayloadTest.ORIGINAL);
        assertThat(jdbc.sql("SELECT published_at IS NOT NULL AND kafka_offset IS NOT NULL FROM test_run_case")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assertion_result WHERE passed = FALSE").query(Integer.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_observation WHERE source = 'KAFKA'").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT passed_cases || '/' || total_cases FROM test_run").query(String.class).single())
                .isEqualTo("1/1");
    }

    static final String XML_EXPECTED = EXPECTED.replace("\"noFailures\"", "\"xml\":{\"exists\":true,\"goldenCompare\":true,"
            + "\"ignore\":[\"CREATED\"]},\"noFailures\"");

    @Test
    void xmlIsFetchedFromS3ComparedWithTheGoldenAndKeptWithTheRun() {
        String golden = "<L><P>A1</P><CREATED>2026-01-01</CREATED></L>";
        long goldenId = artifacts.saveInDb("GOLDEN_XML", "g.xml", "application/xml", golden, "t");
        scenario("gili-owner-print", "GILI", List.of("client:GILI"), XML_EXPECTED, goldenId);
        S3.put("b/base/x.XML", "<L><P>A1</P><CREATED>2031-09-09</CREATED></L>");
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        String run = runAll(null);

        assertThat(status(run)).isEqualTo("PASSED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assertion_result WHERE category = 'XML' AND passed = TRUE")
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT a.kind || '/' || ra.role FROM run_case_artifact ra JOIN artifact a ON a.id = ra.artifact_id""")
                .query(String.class).single()).isEqualTo("RUN_XML/GENERATED_XML");
    }

    @Test
    void xmlThatDiffersFromTheGoldenFailsTheRun() {
        long goldenId = artifacts.saveInDb("GOLDEN_XML", "g.xml", "application/xml", "<L><P>A1</P></L>", "t");
        scenario("gili-owner-print", "GILI", List.of("client:GILI"), XML_EXPECTED, goldenId);
        S3.put("b/base/x.XML", "<L><P>CHANGED</P></L>");
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        String run = runAll(null);

        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case").query(String.class).single()).contains("xml.golden");
    }

    @Test
    void eachRunUsesANewCorrelationId() {
        scenario("gili-owner-print", "GILI", List.of("client:GILI"));
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        runAll(null);
        runAll(null);

        assertThat(SENT).extracting(Sent::key).doesNotHaveDuplicates();
    }

    @Test
    void mismatchFailsTheCaseAndTheRunWithAReadableSummary() {
        scenario("gili-owner-print", "GILI", List.of("client:GILI"));
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "EMAIL"));

        String run = runAll(null);

        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case").query(String.class).single())
                .contains("targets[OWNER].deliveryType");
        assertThat(jdbc.sql("SELECT actual FROM assertion_result WHERE path = 'targets[OWNER].deliveryType'")
                .query(String.class).single()).isEqualTo("EMAIL");
    }

    @Test
    void eventStuckInProcessingTimesOut() {
        scenario("gili-owner-print", "GILI", List.of("client:GILI"));
        jdbc.sql("UPDATE test_case_version SET timeout_seconds = 1").update();
        NGC.put("current", c -> chain(c, "PROCESSING", "PRINT"));

        String run = runAll(null);

        assertThat(jdbc.sql("SELECT status FROM test_run_case").query(String.class).single()).isEqualTo("TIMEOUT");
        assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case").query(String.class).single())
                .startsWith("Timed out after 1s");
        assertThat(status(run)).isEqualTo("FAILED");
    }

    @Test
    void safetyGuardBlocksCarriersOutsideTheAllowList() {
        scenario("other-owner-print", "ZZZZ", List.of("client:GILI"));
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        String run = runAll(null);

        assertThat(SENT).isEmpty();
        assertThat(status(run)).isEqualTo("ERROR");
        assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case").query(String.class).single())
                .contains("Safety guard");
    }

    @Test
    void tagSelectionPicksOnlyMatchingCasesAndRejectsEmptySelection() {
        scenario("gili-owner-print", "GILI", List.of("client:GILI", "event:policy-page"));
        scenario("gili-owner-email", "GILI", List.of("client:GILI", "event:anniversary"));
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        String run = runAll("event:anniversary");

        assertThat(SENT).hasSize(1);
        assertThat(jdbc.sql("SELECT total_cases FROM test_run WHERE run_key = :k").param("k", run)
                .query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> runs.create(new RunRequest("policy-page", null, "qa", "mapper:policy-page", "ui"), "t"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> runs.create(new RunRequest("policy-page", null, "nope", null, "ui"), "t"))
                .isInstanceOf(ValidationException.class);
    }

    void scenarioWithPlans(String key, List<String> plans) {
        cases.save("policy-page", key, new TestCaseRequest(key, null, "SOR", "zinnia.correspondence.sor",
                "policy.activation.completed", "GILI", plans, payload, null, EXPECTED, 600, true, List.of("client:GILI"), "n"), "t");
    }

    @Test
    void eachPlanCodeIsPlayedAsItsOwnEventWithItsOwnCorrelationIdAndPlanCodeInThePayload() throws Exception {
        scenarioWithPlans("gili-three-plans", List.of("GCFIA901", "GCFIA902", "GCFIA903"));
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        String run = runAll(null);

        assertThat(status(run)).isEqualTo("PASSED");
        assertThat(SENT).hasSize(3);
        assertThat(SENT).extracting(Sent::key).doesNotHaveDuplicates();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(SENT.stream().map(s -> {
            try {
                for (var i : mapper.readTree(s.value()).get("identifiers")) {
                    if (i.get("identifier").asText().equals("PLAN_CODE")) {
                        return i.get("value").asText();
                    }
                }
                return "none";
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).containsExactlyInAnyOrder("GCFIA901", "GCFIA902", "GCFIA903");
        assertThat(jdbc.sql("SELECT total_cases || '/' || passed_cases FROM test_run WHERE run_key = :k").param("k", run)
                .query(String.class).single()).isEqualTo("3/3");
        assertThat(jdbc.sql("SELECT plan_code FROM test_run_case ORDER BY id").query(String.class).list())
                .containsExactly("GCFIA901", "GCFIA902", "GCFIA903");
        assertThat(SENT).allSatisfy(s -> assertThat(s.value()).doesNotContainIgnoringCase(PayloadTest.ORIGINAL).contains(s.key()));
    }

    @Test
    void aCaseWithoutPlanCodesStillPlaysOnceWithThePayloadAsUploaded() {
        scenarioWithPlans("gili-no-plans", null);
        NGC.put("current", c -> chain(c, "SENT_TO_CDS", "PRINT"));

        runAll(null);

        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0).value()).contains("GCFIA903");
        assertThat(jdbc.sql("SELECT plan_code FROM test_run_case").query(String.class).optional()).isEmpty();
    }
}
