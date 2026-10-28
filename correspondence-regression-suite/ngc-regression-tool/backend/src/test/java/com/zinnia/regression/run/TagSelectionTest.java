package com.zinnia.regression.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.payload.PayloadTest;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import com.zinnia.regression.testcase.ValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** The seeded NGC mappers, any-of tag selection within a kind, and the per-tag event counts behind the schedule picker. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:tagsel-test;DB_CLOSE_DELAY=-1", "regression.run.large-run-threshold=100000"})
@AutoConfigureMockMvc
@WithMockUser(username = "tester")
class TagSelectionTest {

    static final String EXPECTED = "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}";

    @Autowired MockMvc mvc;
    @Autowired RunService runs;
    @Autowired TestCaseService cases;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    long payload;

    @BeforeEach
    void reset() {
        for (String t : List.of("test_run_case", "test_run", "module_version_case", "module_version", "test_case_version_tag",
                "test_case_version_plan", "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        jdbc.sql("DELETE FROM tag WHERE category = 'CLIENT' AND code = 'ELIC'").update();
        jdbc.sql("INSERT INTO tag (category, code, label) VALUES ('CLIENT', 'ELIC', 'ELIC')").update();
        payload = artifacts.saveInDb("EVENT_PAYLOAD", "p.json", "application/json", PayloadTest.PAYLOAD, "t");
    }

    void add(String key, List<String> plans, boolean enabled, String... tags) {
        cases.save("policy-page", key, new TestCaseRequest(key, null, "SOR", "zinnia.correspondence.sor", "e", "GILI", plans,
                payload, null, EXPECTED, 600, enabled, List.of(tags), "n"), "t");
    }

    /** policy page x2 (GILI, 3 plan codes + 1), anniversary (GILI, 2), anniversary (ELIC, 1), new business (GILI, 1, disabled). */
    void sample() {
        add("pp-three-plans", List.of("A1", "A2", "A3"), true, "client:GILI", "mapper:policy-page");
        add("pp-one-plan", List.of("B1"), true, "client:GILI", "mapper:policy-page");
        add("ann-gili", List.of("C1", "C2"), true, "client:GILI", "mapper:anniversary");
        add("ann-elic", null, true, "client:ELIC", "mapper:anniversary");
        add("nb-disabled", List.of("D1"), false, "client:GILI", "mapper:new-business");
    }

    int events(String tags) {
        return runs.preview(new RunRequest("policy-page", null, "qa", tags, "SCHEDULE")).events();
    }

    // ---- seeded mapper groups -----------------------------------------------------------------------------------
    @Test
    void theElevenNgcMappersAreSeededAsPlainGroupsAndNoEventNamesAreStored() throws Exception {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tag WHERE category = 'MAPPER' AND active = TRUE").query(Integer.class).single()).isEqualTo(11);
        assertThat(jdbc.sql("SELECT active FROM tag WHERE category = 'MAPPER' AND code = 'policy'").query(Boolean.class).single()).isFalse();

        mvc.perform(get("/api/reference/tags?category=mapper")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[?(@.code=='policy-page')].description").value("NGC mapper: PolicyPageXmlMapper"))
                .andExpect(jsonPath("$[?(@.code=='new-business')].label").value("New Business letters"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'MAPPER_EVENT'").query(Integer.class).single())
                .as("the testers map events to groups, the database does not").isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tag WHERE description LIKE '%.completed%' OR description LIKE '%event types%'").query(Integer.class).single()).isZero();
    }

    @Test
    void theHiddenPolicyMapperCannotBeUsedOnNewTestCases() {
        assertThatThrownBy(() -> add("old-tag", null, true, "client:GILI", "mapper:policy"))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors()).contains("Unknown or inactive tag: mapper:policy"));
    }

    // ---- selection --------------------------------------------------------------------------------------------
    @Test
    void severalMappersSelectTheUnionOfTheirEvents() {
        sample();

        assertThat(events("mapper:policy-page")).as("3 + 1 plan codes").isEqualTo(4);
        assertThat(events("mapper:anniversary")).isEqualTo(3);
        assertThat(events("mapper:policy-page,mapper:anniversary")).isEqualTo(7);
        assertThat(events(null)).as("everything enabled").isEqualTo(7);
    }

    @Test
    void differentKindsOfTagMustAllMatch() {
        sample();

        assertThat(events("client:GILI,mapper:anniversary")).isEqualTo(2);
        assertThat(events("client:ELIC,mapper:anniversary")).isEqualTo(1);
        assertThat(events("client:GILI,mapper:policy-page,mapper:anniversary")).isEqualTo(6);
    }

    @Test
    void anEmptySelectionIsRefusedWithAClearMessage() {
        sample();

        assertThatThrownBy(() -> events("client:ELIC,mapper:policy-page"))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors()).contains("No enabled test cases match the selection"));
    }

    // ---- counts for the picker --------------------------------------------------------------------------------
    @Test
    void tagCountsShowCasesAndEventsPerTagIncludingZerosAndLeavingOutDisabledCases() throws Exception {
        sample();

        mvc.perform(get("/api/modules/policy-page/tag-counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_cases").value(4))
                .andExpect(jsonPath("$.total_events").value(7))
                .andExpect(jsonPath("$.tags[?(@.category=='MAPPER' && @.code=='policy-page')].cases").value(2))
                .andExpect(jsonPath("$.tags[?(@.category=='MAPPER' && @.code=='policy-page')].events").value(4))
                .andExpect(jsonPath("$.tags[?(@.category=='MAPPER' && @.code=='anniversary')].events").value(3))
                .andExpect(jsonPath("$.tags[?(@.category=='MAPPER' && @.code=='new-business')].events").value(0))
                .andExpect(jsonPath("$.tags[?(@.category=='MAPPER' && @.code=='adhoc-letter')].cases").value(0))
                .andExpect(jsonPath("$.tags[?(@.category=='CLIENT' && @.code=='GILI')].events").value(6))
                .andExpect(jsonPath("$.tags[?(@.category=='CLIENT' && @.code=='ELIC')].events").value(1))
                .andExpect(jsonPath("$.tags[?(@.category=='MAPPER')].code", org.hamcrest.Matchers.hasSize(11)));
    }

    @Test
    void casesWithoutAMapperAreCountedSoTheyAreNotForgottenWhenSchedulingByGroup() throws Exception {
        add("grouped", List.of("A1", "A2"), true, "client:GILI", "mapper:policy-page");
        add("ungrouped-a", List.of("B1", "B2", "B3"), true, "client:GILI");
        add("ungrouped-b", null, true, "client:GILI", "event:policy-page");
        add("ungrouped-disabled", List.of("C1"), false, "client:GILI");

        mvc.perform(get("/api/modules/policy-page/tag-counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_events").value(6))
                .andExpect(jsonPath("$.no_mapper_cases").value(2))
                .andExpect(jsonPath("$.no_mapper_events").value(4));
    }

    @Test
    void tagCountsCanLookAtAnEarlierVersion() throws Exception {
        add("first", List.of("A1", "A2"), true, "client:GILI", "mapper:policy-page");
        add("second", List.of("B1"), true, "client:GILI", "mapper:policy-page");

        mvc.perform(get("/api/modules/policy-page/tag-counts?versionNo=1")).andExpect(jsonPath("$.total_events").value(2))
                .andExpect(jsonPath("$.version_no").value(1));
        mvc.perform(get("/api/modules/policy-page/tag-counts")).andExpect(jsonPath("$.total_events").value(3))
                .andExpect(jsonPath("$.version_no").value(2));
    }

    @Test
    void aModuleWithNoCasesStillListsEveryTagWithZeros() throws Exception {
        mvc.perform(get("/api/modules/policy-page/tag-counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_events").value(0))
                .andExpect(jsonPath("$.version_no").doesNotExist())
                .andExpect(jsonPath("$.tags[?(@.category=='MAPPER')].code", org.hamcrest.Matchers.hasSize(11)));
    }

    // ---- selection across modules --------------------------------------------------------------------------------
    @Test
    void withoutAModuleEveryModulesNewestTestsTakePartAndCountsSpanThem() throws Exception {
        add("pp-one", List.of("A1"), true, "client:GILI", "mapper:policy-page");
        jdbc.sql("DELETE FROM test_module WHERE code = 'anniversary'").update();
        jdbc.sql("INSERT INTO test_module (code, name) VALUES ('anniversary', 'Anniversary')").update();
        cases.save("anniversary", "ann-one", new TestCaseRequest("ann-one", null, "SOR", "zinnia.correspondence.sor", "e", "GILI",
                List.of("C1", "C2"), payload, null, EXPECTED, 600, true, List.of("client:GILI", "mapper:anniversary"), "n"), "t");

        assertThat(runs.preview(new RunRequest(null, null, "qa", null, "SCHEDULE")).events()).isEqualTo(3);
        assertThat(runs.preview(new RunRequest(null, null, "qa", "mapper:anniversary", "SCHEDULE")).events()).isEqualTo(2);
        assertThat(runs.preview(new RunRequest(null, null, "qa", "mapper:anniversary,mapper:policy-page", "SCHEDULE")).events()).isEqualTo(3);
        assertThatThrownBy(() -> events("mapper:anniversary")).as("a module given still narrows to that module")
                .isInstanceOf(ValidationException.class);

        String key = runs.create(new RunRequest(null, null, "qa", "mapper:anniversary", "SCHEDULE"), "t");
        assertThat(jdbc.sql("SELECT module_version_id IS NULL FROM test_run WHERE run_key = :k").param("k", key)
                .query(Boolean.class).single()).isTrue();

        mvc.perform(get("/api/modules/cases")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.module=='anniversary')].scenario_key").value("ann-one"))
                .andExpect(jsonPath("$[?(@.module=='policy-page')].scenario_key").value("pp-one"));
        mvc.perform(get("/api/modules/tag-counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_events").value(3))
                .andExpect(jsonPath("$.tags[?(@.code=='anniversary' && @.category=='MAPPER')].events").value(2))
                .andExpect(jsonPath("$.tags[?(@.code=='policy-page' && @.category=='MAPPER')].events").value(1));
    }
}
