package com.zinnia.regression.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.payload.PayloadTest;
import com.zinnia.regression.testcase.SaveResult;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:rec-test;DB_CLOSE_DELAY=-1")
class ObservationRecorderTest {

    static final String CORR = "cccccccc-1111-4222-8333-dddddddddddd";

    @Autowired ObservationRecorder recorder;
    @Autowired JdbcClient jdbc;
    @Autowired TestCaseService service;
    @Autowired ArtifactRepository artifacts;

    long runCase;

    @BeforeEach
    void seed() {
        for (String t : List.of("run_observation", "test_run_case", "test_run", "module_version_case", "module_version",
                "test_case_version_tag", "test_case_version_plan", "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        long payload = artifacts.saveInDb("EVENT_PAYLOAD", "p.json", "application/json", PayloadTest.PAYLOAD, "t");
        SaveResult s = service.save("policy-page", "gili-rec-case", new TestCaseRequest("n", null, "SOR",
                "zinnia.correspondence.sor", "e", "GILI", null, payload, null,
                "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}", 600, true, List.of("client:GILI"), "x"), "t");
        long env = jdbc.sql("SELECT id FROM environment WHERE code='qa'").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO test_run (run_key, module_version_id, environment_id, trigger_source, status)
                VALUES ('r', :mv, :env, 'CLI', 'RUNNING')""").param("mv", s.moduleVersionId()).param("env", env).update();
        long run = jdbc.sql("SELECT id FROM test_run").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO test_run_case (run_id, test_case_version_id, correlation_id, topic_name, status)
                VALUES (:r, :t, :c, 'zinnia.correspondence.sor', 'RUNNING')""")
                .param("r", run).param("t", s.testCaseVersionId()).param("c", CORR).update();
        runCase = jdbc.sql("SELECT id FROM test_run_case").query(Long.class).single();
    }

    Observation obs(String collection, String doc, String status) {
        return new Observation(OffsetDateTime.now(), "MONGO", collection, "ev", status, "OWNER", "ngc-1", null, null,
                null, doc, null);
    }

    NgcView view(Observation... o) {
        var incoming = new NgcView.IncomingEventView("ngc-1", "SENT_TO_CDS", null, "base", List.of());
        return new NgcView(CORR, List.of(incoming), 1, 0, 0, List.of(o));
    }

    @Test
    void storesObservationsAndSyncsRunCaseFromIncomingEvent() {
        int added = recorder.record(runCase, CORR, view(obs("incoming_events", "e1", "SENT_TO_CDS"),
                obs("cds_events", "c1", "SENT")));

        assertThat(added).isEqualTo(2);
        assertThat(jdbc.sql("SELECT ngc_unique_id || '/' || final_incoming_status FROM test_run_case")
                .query(String.class).single()).isEqualTo("ngc-1/SENT_TO_CDS");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_observation WHERE correlation_id = :c").param("c", CORR)
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void pollingAgainAddsOnlyNewRecordsAndStatusTransitions() {
        recorder.record(runCase, CORR, view(obs("incoming_events", "e1", "PROCESSING")));

        int again = recorder.record(runCase, CORR, view(obs("incoming_events", "e1", "PROCESSING"),
                obs("incoming_events", "e1", "SENT_TO_CDS"), obs("internal_events", "i1", "PROCESSED")));

        assertThat(again).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_observation").query(Integer.class).single()).isEqualTo(3);
    }
}
