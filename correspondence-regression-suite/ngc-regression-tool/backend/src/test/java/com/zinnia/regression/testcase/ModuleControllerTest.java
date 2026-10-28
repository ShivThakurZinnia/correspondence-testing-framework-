package com.zinnia.regression.testcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.payload.PayloadTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:module-ctl-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ModuleControllerTest {

    static final String EXPECTED = "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}";

    @Autowired MockMvc mvc;
    @Autowired TestCaseService service;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    long payload;

    @BeforeEach
    void seed() {
        for (String t : List.of("module_version_case", "module_version", "test_case_version_tag", "test_case_version_plan", "test_case_version",
                "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        payload = artifacts.saveInDb("EVENT_PAYLOAD", "p.json", "application/json", PayloadTest.PAYLOAD, "t");
        save("gili-a", "Case A", EXPECTED);
        save("gili-b", "Case B", EXPECTED);
        save("gili-a", "Case A", EXPECTED.replace("SENT_TO_CDS", "PROCESSED"));
        save("gili-c", "Case C", EXPECTED);
    }

    void save(String key, String name, String expected) {
        service.save("policy-page", key, new TestCaseRequest(name, "desc", "SOR", "zinnia.correspondence.sor", "e", "GILI",
                List.of("GCFIA903"), payload, null, expected, 120, true, List.of("client:GILI", "event:policy-page"), "n"), "t");
    }

    @Test
    void caseDetailReturnsTheLatestRevisionWithEverythingTheEditorNeeds() throws Exception {
        mvc.perform(get("/api/modules/policy-page/cases/gili-a").with(httpBasic("tester", "test-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.name").value("Case A"))
                .andExpect(jsonPath("$.kafka_kind").value("SOR"))
                .andExpect(jsonPath("$.payload_artifact_id").value((int) payload))
                .andExpect(jsonPath("$.timeout_seconds").value(120))
                .andExpect(jsonPath("$.tags").value("client:GILI,event:policy-page"))
                .andExpect(jsonPath("$.expected_json").value(org.hamcrest.Matchers.containsString("PROCESSED")));
        mvc.perform(get("/api/modules/policy-page/cases/nope").with(httpBasic("tester", "test-pass")))
                .andExpect(status().isNotFound());
    }

    @Test
    void planCodesAreReturnedCommaSeparatedInOrder() throws Exception {
        service.save("policy-page", "gili-plans", new TestCaseRequest("Plans", null, "SOR", "zinnia.correspondence.sor", "e",
                "GILI", List.of("GCFIA903", "GCFIA901", "TL0101"), payload, null, EXPECTED, 120, true, List.of("client:GILI"), "n"), "t");

        mvc.perform(get("/api/modules/policy-page/cases/gili-plans").with(httpBasic("tester", "test-pass")))
                .andExpect(jsonPath("$.plan_codes").value("GCFIA903,GCFIA901,TL0101"));
        mvc.perform(get("/api/modules/policy-page/versions/5/cases").with(httpBasic("tester", "test-pass")))
                .andExpect(jsonPath("$[?(@.scenario_key=='gili-plans')].plan_codes").value("GCFIA903,GCFIA901,TL0101"));
    }

    @Test
    void revisionsListNewestFirst() throws Exception {
        mvc.perform(get("/api/modules/policy-page/cases/gili-a/revisions").with(httpBasic("tester", "test-pass")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].revision").value(2))
                .andExpect(jsonPath("$[1].revision").value(1));
    }

    @Test
    void diffShowsAddedChangedAndUnchangedScenariosBetweenVersions() throws Exception {
        // versions: 1={a1} 2={a1,b1} 3={a2,b1} 4={a2,b1,c1}
        mvc.perform(get("/api/modules/policy-page/versions/2/diff/4").with(httpBasic("tester", "test-pass")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.scenario_key=='gili-a')].change").value("CHANGED"))
                .andExpect(jsonPath("$[?(@.scenario_key=='gili-c')].change").value("ADDED"));
        mvc.perform(get("/api/modules/policy-page/versions/4/diff/1").with(httpBasic("tester", "test-pass")))
                .andExpect(jsonPath("$[?(@.scenario_key=='gili-b')].change").value("REMOVED"));
        mvc.perform(get("/api/modules/policy-page/versions/3/diff/3").with(httpBasic("tester", "test-pass")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void artifactContentIsServedAndUnknownIsNotFound() throws Exception {
        mvc.perform(get("/api/artifacts/" + payload).with(httpBasic("tester", "test-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("EVENT_PAYLOAD"))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("policy.activation.completed")));
        mvc.perform(get("/api/artifacts/999999").with(httpBasic("tester", "test-pass"))).andExpect(status().isNotFound());
    }

    @Test
    void unauthorisedApiCallsGetPlain401WithoutABrowserLoginPromptButStaticFilesAreOpen() throws Exception {
        var result = mvc.perform(get("/api/modules")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
        mvc.perform(get("/index.html")).andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }
}
