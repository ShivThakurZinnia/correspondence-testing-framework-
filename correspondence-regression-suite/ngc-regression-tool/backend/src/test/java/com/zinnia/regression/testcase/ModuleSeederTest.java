package com.zinnia.regression.testcase;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.payload.EventPayloadValidator;
import com.zinnia.regression.run.RunRequest;
import com.zinnia.regression.run.RunService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:seeder-test;DB_CLOSE_DELAY=-1", "regression.run.large-run-threshold=100000"})
class ModuleSeederTest {

    @Autowired ModuleSeeder seeder;
    @Autowired JdbcClient jdbc;
    @Autowired RunService runs;
    @Autowired EventPayloadValidator validator;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void reset() {
        for (String t : List.of("test_run_case", "test_run", "module_version_case", "module_version", "test_case_version_tag",
                "test_case_version_plan", "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        jdbc.sql("DELETE FROM test_module WHERE code <> 'policy-page'").update();
    }

    @Test
    void createsTheFourModulesWithTheirTestCases() throws Exception {
        var r = seeder.seed();

        assertThat(r.seededModules()).containsExactlyInAnyOrder("policy-page", "anniversary", "new-business", "policy");
        assertThat(r.cases()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT code FROM test_module ORDER BY code").query(String.class).list())
                .containsExactly("anniversary", "new-business", "policy", "policy-page");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM test_case").query(Integer.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM module_version").query(Integer.class).single()).as("each save rolls its module").isEqualTo(6);
    }

    @Test
    void everySeededFieldMatchesWhatDevShows() throws Exception {
        seeder.seed();

        Map<String, Object> pp = jdbc.sql("""
                SELECT v.kafka_kind, v.kafka_topic_pattern, v.event_type, v.carrier, v.timeout_seconds, v.enabled, v.expected_json, v.description
                FROM test_case_version v JOIN test_case c ON c.id = v.test_case_id WHERE c.scenario_key = 'gili-policy-page-owner-print'
                """).query().singleRow();
        assertThat(pp).containsEntry("KAFKA_KIND", "SOR").containsEntry("KAFKA_TOPIC_PATTERN", "zinnia.correspondence.sor")
                .containsEntry("EVENT_TYPE", "policy.activation.completed").containsEntry("CARRIER", "GILI");
        JsonNode expected = mapper.readTree((String) pp.get("EXPECTED_JSON"));
        assertThat(expected.at("/targets/0/documentType").asText()).isEqualTo("POLPG");
        assertThat(expected.at("/targets/0/deliveryType").asText()).isEqualTo("PRINT");
        assertThat((String) pp.get("DESCRIPTION")).contains("PLACEHOLDER", "REPLACE_ME");

        assertThat(planCodes("gili-policy-page-owner-print")).containsExactly("GCFIA901", "GCFIA902", "GCFIA903");
        assertThat(planCodes("gili-anniversary-gcfia901")).containsExactly("GCFIA901");
        assertThat(planCodes("gili-anniversary-gcfia902")).containsExactly("GCFIA902");

        assertThat(jdbc.sql("""
                SELECT v.kafka_kind || '/' || v.kafka_topic_pattern FROM test_case_version v JOIN test_case c ON c.id = v.test_case_id
                WHERE c.scenario_key = 'gili-transfer-of-assets-gcfia901'""").query(String.class).single())
                .as("transfer of assets arrives on the BPM topic").isEqualTo("BPM/zinnia.correspondence.{env}");
        assertThat(jdbc.sql("""
                SELECT v.expected_json FROM test_case_version v JOIN test_case c ON c.id = v.test_case_id
                WHERE c.scenario_key = 'gili-transfer-of-assets-gcfia901'""").query(String.class).single())
                .contains("ORGANIZATION", "\"agentType\":\"NA\"");
        assertThat(jdbc.sql("""
                SELECT v.expected_json FROM test_case_version v JOIN test_case c ON c.id = v.test_case_id
                WHERE c.scenario_key = 'gili-anniversary-gcfia902'""").query(String.class).single())
                .as("delivery type varies by plan in Dev, so it is not asserted").doesNotContain("deliveryType");
    }

    @Test
    void everySeededCaseIsInAMapperGroupSoItCanBeScheduledByGroup() throws Exception {
        seeder.seed();

        assertThat(jdbc.sql("""
                SELECT c.scenario_key FROM test_case c JOIN test_case_version v ON v.test_case_id = c.id
                WHERE NOT EXISTS (SELECT 1 FROM test_case_version_tag vt JOIN tag t ON t.id = vt.tag_id
                                  WHERE vt.test_case_version_id = v.id AND t.category = 'MAPPER')
                """).query(String.class).list()).isEmpty();
    }

    @Test
    void placeholderPayloadsAreValidEventsAndClearlyMarked() throws Exception {
        seeder.seed();

        for (String content : jdbc.sql("SELECT content FROM artifact WHERE kind = 'EVENT_PAYLOAD'").query(String.class).list()) {
            var info = validator.validate(content);
            assertThat(info.valid()).as(info.errors().toString()).isTrue();
            assertThat(info.carrier()).isEqualTo("GILI");
            assertThat(info.policyNumber()).isEqualTo("REPLACE_ME");
            assertThat(info.planCode()).startsWith("GCFIA9");
        }
    }

    @Test
    void neverTouchesAModuleThatAlreadyHasWorkAndIsSafeToRunAgain() throws Exception {
        seeder.seed();
        int cases = jdbc.sql("SELECT COUNT(*) FROM test_case").query(Integer.class).single();
        int versions = jdbc.sql("SELECT COUNT(*) FROM module_version").query(Integer.class).single();

        var again = seeder.seed();

        assertThat(again.seededModules()).isEmpty();
        assertThat(again.skippedModules()).hasSize(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM test_case").query(Integer.class).single()).isEqualTo(cases);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM module_version").query(Integer.class).single()).isEqualTo(versions);
    }

    @Test
    void aModuleTheTesterAlreadyStartedIsLeftAloneWhileTheOthersAreSeeded() throws Exception {
        jdbc.sql("INSERT INTO test_module (code, name) VALUES ('anniversary', 'Anniversary')").update();
        jdbc.sql("INSERT INTO module_version (module_id, version_no, version, content_hash, change_note, created_by) "
                + "SELECT id, 1, '1.0.0', 'x', 'mine', 'tester' FROM test_module WHERE code = 'anniversary'").update();

        var r = seeder.seed();

        assertThat(r.skippedModules()).contains("anniversary");
        assertThat(r.seededModules()).containsExactlyInAnyOrder("policy-page", "new-business", "policy");
    }

    @Test
    void theSeededCasesAreRunnableSelectionsPerMapper() throws Exception {
        seeder.seed();

        assertThat(runs.preview(new RunRequest("policy-page", null, "qa", "mapper:policy-page", "SCHEDULE")).events()).isEqualTo(3);
        assertThat(runs.preview(new RunRequest("anniversary", null, "qa", "mapper:anniversary", "SCHEDULE")).events()).isEqualTo(2);
        assertThat(runs.preview(new RunRequest("new-business", null, "qa", "mapper:new-business", "SCHEDULE")).events()).isEqualTo(2);
        assertThat(runs.preview(new RunRequest("policy", null, "qa", "mapper:automated-letter", "SCHEDULE")).events()).isEqualTo(1);
    }

    private List<String> planCodes(String key) {
        return jdbc.sql("""
                SELECT p.plan_code FROM test_case_version_plan p JOIN test_case_version v ON v.id = p.test_case_version_id
                JOIN test_case c ON c.id = v.test_case_id WHERE c.scenario_key = :k ORDER BY p.position
                """).param("k", key).query(String.class).list();
    }
}
