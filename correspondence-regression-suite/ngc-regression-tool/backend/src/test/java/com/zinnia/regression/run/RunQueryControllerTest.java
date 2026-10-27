package com.zinnia.regression.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.payload.PayloadTest;
import com.zinnia.regression.testcase.SaveResult;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:runq-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser(username = "tester")
class RunQueryControllerTest {

    static final String CORR = "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TestCaseService service;
    @Autowired ArtifactRepository artifacts;

    @BeforeEach
    void seed() {
        for (String t : List.of("run_observation", "assertion_result", "run_case_artifact", "test_run_case", "test_run",
                "module_version_case", "module_version", "test_case_version_tag", "test_case_version_plan", "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        long payload = artifacts.saveInDb("EVENT_PAYLOAD", "p.json", "application/json", PayloadTest.PAYLOAD, "t");
        SaveResult saved = service.save("policy-page", "gili-policy-page-owner-print", new TestCaseRequest(
                "Owner print", null, "SOR", "zinnia.correspondence.sor", "policy.activation.completed", "GILI",
                List.of("GCFIA903"), payload, null, "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}", 600, true,
                List.of("client:GILI", "event:policy-page"), "seed"), "t");

        long env = jdbc.sql("SELECT id FROM environment WHERE code = 'qa'").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO test_run (run_key, module_version_id, environment_id, trigger_source, triggered_by, status,
                  started_at, finished_at, total_cases, passed_cases, failed_cases, tag_expression)
                VALUES ('run-1', :mv, :env, 'SCHEDULE', 'scheduler', 'FAILED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0, 1, 'client:GILI')
                """).param("mv", saved.moduleVersionId()).param("env", env).update();
        long run = jdbc.sql("SELECT id FROM test_run WHERE run_key = 'run-1'").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO test_run_case (run_id, test_case_version_id, correlation_id, topic_name, status,
                  final_incoming_status, failure_summary, published_at)
                VALUES (:run, :tcv, :corr, 'zinnia.correspondence.sor', 'FAILED', 'SENT_TO_CDS',
                  'pdf.generation.completed not received', CURRENT_TIMESTAMP)
                """).param("run", run).param("tcv", saved.testCaseVersionId()).param("corr", CORR).update();
        long rc = jdbc.sql("SELECT id FROM test_run_case").query(Long.class).single();
        observe(rc, "MONGO", "incoming_events", null, "SENT_TO_CDS");
        observe(rc, "MONGO", "target_role_and_event_status_audit", "XML Generated", "XML Generated");
        observe(rc, "MONGO", "cds_events", "policy.activation.completed", null);
        observe(rc, "MONGO", "internal_events", "pdf.generation.started", "PROCESSED");
        jdbc.sql("""
                INSERT INTO assertion_result (run_case_id, category, path, expected, actual, passed, message)
                VALUES (:rc, 'CALLBACK', 'pdf.generation.completed', 'received', 'missing', FALSE, 'timed out')
                """).param("rc", rc).update();
    }

    private void observe(long rc, String source, String collection, String eventType, String status) {
        jdbc.sql("""
                INSERT INTO run_observation (run_case_id, occurred_at, source, collection, event_type, status, correlation_id)
                VALUES (:rc, CURRENT_TIMESTAMP, :s, :c, :e, :st, :corr)
                """).param("rc", rc).param("s", source).param("c", collection).param("e", eventType)
                .param("st", status).param("corr", CORR).update();
    }

    @Test
    void dashboardShowsTilePerEnvAndScopeWithLastResultAndRecentRuns() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tiles[?(@.env=='qa' && @.scope == 'client:GILI')].last_status").value("FAILED"))
                .andExpect(jsonPath("$.tiles[?(@.env=='qa' && @.scope == 'client:GILI')].runs_7d").value(1))
                .andExpect(jsonPath("$.recent[0].run_key").value("run-1"))
                .andExpect(jsonPath("$.recent[0].module_version").value("1.0.0"));
    }

    @Test
    void dashboardListsUpcomingEnabledSchedulesSoonestFirst() throws Exception {
        jdbc.sql("DELETE FROM schedule").update();
        long module = jdbc.sql("SELECT id FROM test_module WHERE code = 'policy-page'").query(Long.class).single();
        long env = jdbc.sql("SELECT id FROM environment WHERE code = 'qa'").query(Long.class).single();
        for (String[] s : new String[][]{{"late", "0 0 0 1 1 *", "TRUE"}, {"early", "0 * * * * *", "TRUE"},
                {"off", "0 0 1 * * *", "FALSE"}}) {
            jdbc.sql("""
                    INSERT INTO schedule (name, module_id, environment_id, cron_expression, timezone, enabled, created_by)
                    VALUES (:n, :m, :e, :c, 'UTC', :en, 't')
                    """).param("n", s[0]).param("m", module).param("e", env).param("c", s[1])
                    .param("en", Boolean.parseBoolean(s[2])).update();
        }

        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming.length()").value(2))
                .andExpect(jsonPath("$.upcoming[0].name").value("early"))
                .andExpect(jsonPath("$.upcoming[1].name").value("late"))
                .andExpect(jsonPath("$.upcoming[0].next_fire_at").isNotEmpty());
        jdbc.sql("DELETE FROM schedule").update();
    }

    @Test
    void runListFiltersByEnvStatusAndTrigger() throws Exception {
        mvc.perform(get("/api/runs?env=qa&status=failed&trigger=schedule"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/runs?env=uat")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/runs?status=PASSED")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void runDetailListsEventsWithStageProgress() throws Exception {
        mvc.perform(get("/api/runs/run-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.cases[0].correlation_id").value(CORR))
                .andExpect(jsonPath("$.cases[0].progress[?(@.code=='INJECTED')].reached").value(true))
                .andExpect(jsonPath("$.cases[0].progress[?(@.code=='XML_GENERATED')].reached").value(true))
                .andExpect(jsonPath("$.cases[0].progress[?(@.code=='SENT_TO_CDS')].reached").value(true))
                .andExpect(jsonPath("$.cases[0].progress[?(@.code=='PDF_STARTED')].reached").value(true))
                .andExpect(jsonPath("$.cases[0].progress[?(@.code=='PDF_COMPLETED')].reached").value(false));
        mvc.perform(get("/api/runs/nope")).andExpect(status().isNotFound());
    }

    @Test
    void eventDetailGivesTimelineAndFailingAssertionFirst() throws Exception {
        mvc.perform(get("/api/events/" + CORR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario_key").value("gili-policy-page-owner-print"))
                .andExpect(jsonPath("$.timeline.length()").value(4))
                .andExpect(jsonPath("$.assertions[0].passed").value(false))
                .andExpect(jsonPath("$.failure_summary").value("pdf.generation.completed not received"));
    }

    @Test
    void historicEventSearchByCorrelationFragmentTagAndStatus() throws Exception {
        mvc.perform(get("/api/events?correlationId=bbbbbbbb")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/events?tag=client:GILI&status=failed")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/events?tag=client:OTHER")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/events?scenario=gili-policy-page-owner-print&env=uat"))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
