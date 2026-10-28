package com.zinnia.regression.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real Kafka producer and real (read-only) NGC Mongo reads through the whole orchestrator. Events go to a throwaway
 * topic that no NGC reads, so NGC never sees them: the run must publish all of them and then time out, which proves
 * the plumbing without touching NGC. Runs only when KAFKA_BOOTSTRAP_SERVER and MONGODB_URI are set.
 */
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:live-smoke;DB_CLOSE_DELAY=-1", "ngc.environment=dev",
        "regression.allowed-carriers=GILI", "regression.poll-interval-ms=500", "regression.run.events-per-second=50",
        "regression.health.warm-up-on-start=false"})
class BulkRunLiveSmokeTest {

    static final String PAYLOAD = """
            {"id":"5f1c2a9e8b7d4e3a1c0d9b8a","source":"zahara","origin":"DAML","specversion":"1","type":"policy.anniversary.completed",
             "correlationid":"11111111-2222-4333-8444-555555555555","carrier":"GILI","datacontenttype":"application/json",
             "time":"2026-10-27T10:00:00.000Z",
             "data":"{\\"policyNumber\\":\\"REGR-SMOKE1\\",\\"transactionId\\":\\"1\\",\\"planCode\\":\\"GCFIA901\\",\\"status\\":\\"Completed\\"}",
             "identifiers":[{"identifier":"transactionId","value":"1"},{"identifier":"planCode","value":"GCFIA901"},
                            {"identifier":"policyNumber","value":"REGR-SMOKE1"}]}
            """;

    @Autowired RunService runs;
    @Autowired RunExecutor executor;
    @Autowired TestCaseService cases;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    @Test
    void publishesEveryPlanCodeToKafkaReadsNgcInBatchesAndTimesOutCleanlyWhenNgcNeverSeesIt() throws Exception {
        String bootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVER");
        String topic = "regression-suite-smoke-" + UUID.randomUUID();
        Properties admin = new Properties();
        admin.put("bootstrap.servers", bootstrap);
        try (AdminClient client = AdminClient.create(admin)) {
            client.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
            try {
                jdbc.sql("INSERT INTO kafka_topic (environment_id, kind, topic_name) SELECT id, 'SOR', :t FROM environment WHERE code = 'dev'")
                        .param("t", topic).update();
                long payload = artifacts.saveInDb("EVENT_PAYLOAD", "smoke.json", "application/json", PAYLOAD, "smoke");
                cases.save("policy-page", "smoke-anniversary", new TestCaseRequest("Smoke", null, "SOR", topic,
                        "policy.anniversary.completed", "GILI", List.of("GCFIA901", "GCFIA902", "GCFIA903"), payload, null,
                        "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}", 5, true, List.of("client:GILI", "mapper:anniversary"), "smoke"), "t");

                String run = runs.create(new RunRequest("policy-page", null, "dev", "mapper:anniversary", "cli"), "smoke");
                long start = System.currentTimeMillis();
                executor.execute(run);
                long seconds = (System.currentTimeMillis() - start) / 1000;

                System.out.println("LIVE run finished in " + seconds + "s status=" + jdbc.sql("SELECT status FROM test_run WHERE run_key = :k").param("k", run).query(String.class).single());
                assertThat(jdbc.sql("SELECT status FROM test_run WHERE run_key = :k").param("k", run).query(String.class).single()).isEqualTo("FAILED");
                assertThat(jdbc.sql("SELECT COUNT(*) FROM test_run_case WHERE status = 'TIMEOUT'").query(Integer.class).single()).isEqualTo(3);
                assertThat(jdbc.sql("SELECT COUNT(*) FROM test_run_case WHERE published_at IS NOT NULL AND kafka_offset IS NOT NULL").query(Integer.class).single()).isEqualTo(3);
                assertThat(jdbc.sql("SELECT failure_summary FROM test_run_case LIMIT 1").query(String.class).single()).startsWith("Timed out after 5s");
                assertThat(jdbc.sql("SELECT actual FROM assertion_result WHERE path = 'incomingEvent.status' LIMIT 1").query(String.class).single())
                        .isEqualTo("no incoming_events record");
                assertThat(seconds).as("one 5 s deadline for all three, not 15 s").isLessThan(14);

                // what really reached Kafka
                Properties c = new Properties();
                c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                c.put(ConsumerConfig.GROUP_ID_CONFIG, "regression-smoke-" + UUID.randomUUID());
                c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                List<ConsumerRecord<String, String>> received = new ArrayList<>();
                try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
                    consumer.subscribe(List.of(topic));
                    long end = System.currentTimeMillis() + 15000;
                    while (received.size() < 3 && System.currentTimeMillis() < end) {
                        consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                    }
                }
                assertThat(received).hasSize(3);
                ObjectMapper mapper = new ObjectMapper();
                List<String> plans = new ArrayList<>();
                List<String> registered = jdbc.sql("SELECT correlation_id FROM test_run_case").query(String.class).list();
                for (ConsumerRecord<String, String> r : received) {
                    JsonNode json = mapper.readTree(r.value());
                    assertThat(r.key()).isEqualTo(json.get("correlationid").asText()).isIn(registered);
                    assertThat(r.value()).doesNotContainIgnoringCase("11111111-2222-4333-8444-555555555555");
                    String plan = json.get("identifiers").get(1).get("value").asText();
                    plans.add(plan);
                    assertThat(mapper.readTree(json.get("data").asText()).get("planCode").asText()).as("planCode inside data follows").isEqualTo(plan);
                }
                assertThat(plans).containsExactlyInAnyOrder("GCFIA901", "GCFIA902", "GCFIA903");
                System.out.println("LIVE kafka received " + received.size() + " events with plan codes " + plans);
            } finally {
                client.deleteTopics(List.of(topic)).all().get();
            }
        }
    }
}
