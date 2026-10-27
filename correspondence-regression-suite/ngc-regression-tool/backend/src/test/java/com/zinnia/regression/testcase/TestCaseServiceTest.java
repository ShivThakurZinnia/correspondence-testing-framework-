package com.zinnia.regression.testcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.payload.PayloadTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tcs-test;DB_CLOSE_DELAY=-1")
class TestCaseServiceTest {

    static final String EXPECTED = "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"},"
            + "\"targets\":[{\"role\":\"OWNER\",\"deliveryType\":\"PRINT\"}]}";

    @Autowired TestCaseService service;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    long payloadId;

    @BeforeEach
    void clean() {
        for (String t : List.of("module_version_case", "module_version", "test_case_version_tag", "test_case_version_plan",
                "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        payloadId = artifacts.saveInDb("EVENT_PAYLOAD", "p.json", "application/json", PayloadTest.PAYLOAD, "t");
    }

    TestCaseRequest request(String name, String expected) {
        return new TestCaseRequest(name, null, "SOR", "zinnia.correspondence.sor", "policy.activation.completed",
                "GILI", List.of("GCFIA903"), payloadId, null, expected, 600, true,
                List.of("client:GILI", "event:policy-page", "mapper:policy-page"), "initial");
    }

    @Test
    void firstSaveCreatesRevisionOneAndModuleVersionOne() {
        SaveResult r = service.save("policy-page", "gili-policy-page-owner-print", request("Owner print", EXPECTED), "u");

        assertThat(r.changed()).isTrue();
        assertThat(r.revision()).isEqualTo(1);
        assertThat(r.moduleVersion()).isEqualTo("1.0.0");
        assertThat(jdbc.sql("SELECT payload_correlation_id FROM test_case_version WHERE id = :i").param("i", r.testCaseVersionId())
                .query(String.class).single()).isEqualTo(PayloadTest.ORIGINAL);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM test_case_version_tag").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void savingIdenticalContentDoesNotCreateAVersion() {
        service.save("policy-page", "gili-policy-page-owner-print", request("Owner print", EXPECTED), "u");

        SaveResult again = service.save("policy-page", "gili-policy-page-owner-print",
                request("Owner print", EXPECTED), "u");

        assertThat(again.changed()).isFalse();
        assertThat(again.revision()).isEqualTo(1);
        assertThat(again.moduleVersion()).isEqualTo("1.0.0");
        assertThat(count("module_version")).isEqualTo(1);
    }

    @Test
    void jsonKeyOrderAndWhitespaceDoNotCountAsAChange() {
        service.save("policy-page", "gili-policy-page-owner-print", request("Owner print", EXPECTED), "u");
        String reordered = "{ \"targets\":[{\"deliveryType\":\"PRINT\",\"role\":\"OWNER\"}],"
                + " \"incomingEvent\":{\"status\":\"SENT_TO_CDS\"} }";

        assertThat(service.save("policy-page", "gili-policy-page-owner-print",
                request("Owner print", reordered), "u").changed()).isFalse();
    }

    @Test
    void changingExpectedCreatesNewRevisionAndNewModuleVersionKeepingOldOnesIntact() {
        SaveResult v1 = service.save("policy-page", "gili-policy-page-owner-print", request("Owner print", EXPECTED), "u");

        SaveResult v2 = service.save("policy-page", "gili-policy-page-owner-print",
                request("Owner print", EXPECTED.replace("PRINT", "EMAIL")), "u");

        assertThat(v2.changed()).isTrue();
        assertThat(v2.revision()).isEqualTo(2);
        assertThat(v2.moduleVersion()).isEqualTo("1.1.0");
        assertThat(jdbc.sql("SELECT expected_json FROM test_case_version WHERE id = :i").param("i", v1.testCaseVersionId())
                .query(String.class).single()).contains("PRINT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM module_version_case WHERE module_version_id = :m")
                .param("m", v1.moduleVersionId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void secondScenarioRollsModuleVersionAndSharesUnchangedRevision() {
        service.save("policy-page", "gili-policy-page-owner-print", request("Owner print", EXPECTED), "u");

        SaveResult second = service.save("policy-page", "gili-policy-page-owner-email",
                request("Owner email", EXPECTED.replace("PRINT", "EMAIL")), "u");

        assertThat(second.moduleVersion()).isEqualTo("1.1.0");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM module_version_case WHERE module_version_id = :m")
                .param("m", second.moduleVersionId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(count("test_case_version")).isEqualTo(2);
    }

    @Test
    void approvalRecordsApprover() {
        SaveResult r = service.save("policy-page", "gili-policy-page-owner-print", request("Owner print", EXPECTED), "u");

        service.setStatus(r.moduleVersionId(), "APPROVED", "qa-lead");

        assertThat(jdbc.sql("SELECT approved_by FROM module_version WHERE id = :i").param("i", r.moduleVersionId())
                .query(String.class).single()).isEqualTo("qa-lead");
    }

    @Test
    void rejectsMissingTopicChoiceUnknownTopicNoClientTagAndSilentPassExpectations() {
        TestCaseRequest bad = new TestCaseRequest("x", null, null, null, "e", "GILI", null, payloadId, null,
                "{\"targets\":[]}", null, null, List.of("event:policy-page", "client:NOPE"), null);

        assertThatThrownBy(() -> service.save("policy-page", "gili-bad-case", bad, "u"))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors()).contains(
                        "kafkaKind is required and must be SOR or BPM (the tester must choose the topic)",
                        "expectedJson must assert incomingEvent.status",
                        "Unknown or inactive tag: client:NOPE",
                        "At least one client:<code> tag is required"));

        TestCaseRequest wrongTopic = new TestCaseRequest("x", null, "SOR", "zinnia.correspondence.nope", "e", "GILI",
                null, payloadId, null, EXPECTED, null, null, List.of("client:GILI"), null);
        assertThatThrownBy(() -> service.save("policy-page", "gili-bad-case", wrongTopic, "u"))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.errors().get(0)).contains("matches no configured SOR topic"));
        assertThat(count("test_case")).isZero();
    }

    @Test
    void bpmTopicPatternResolvesPerEnvironment() {
        TestCaseRequest bpm = new TestCaseRequest("bpm", null, "BPM", "zinnia.correspondence.{env}", "e", "GILI",
                null, payloadId, null, EXPECTED, null, null, List.of("client:GILI"), null);

        assertThat(service.save("policy-page", "gili-bpm-case", bpm, "u").changed()).isTrue();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    TestCaseRequest withPlans(List<String> plans) {
        return new TestCaseRequest("Multi plan", null, "SOR", "zinnia.correspondence.sor", "policy.activation.completed", "GILI",
                plans, payloadId, null, EXPECTED, 600, true, List.of("client:GILI"), "n");
    }

    List<String> storedPlans(long versionId) {
        return jdbc.sql("SELECT plan_code FROM test_case_version_plan WHERE test_case_version_id = :v ORDER BY position")
                .param("v", versionId).query(String.class).list();
    }

    @Test
    void severalPlanCodesAreStoredInTheOrderGivenWithBlanksAndRepeatsDropped() {
        SaveResult r = service.save("policy-page", "gili-many-plans",
                withPlans(List.of(" GCFIA901", "GCFIA902 ", "", "gcfia901", "GCFIA903")), "u");

        assertThat(storedPlans(r.testCaseVersionId())).containsExactly("GCFIA901", "GCFIA902", "GCFIA903");
        assertThat(jdbc.sql("SELECT plan_code FROM test_case_version WHERE id = :v").param("v", r.testCaseVersionId())
                .query(String.class).single()).as("first code kept for older readers").isEqualTo("GCFIA901");
    }

    @Test
    void addingOrRemovingAPlanCodeIsAChangeButReorderingIsNot() {
        service.save("policy-page", "gili-many-plans", withPlans(List.of("A1", "B2")), "u");

        assertThat(service.save("policy-page", "gili-many-plans", withPlans(List.of("B2", "A1")), "u").changed()).isFalse();
        SaveResult added = service.save("policy-page", "gili-many-plans", withPlans(List.of("A1", "B2", "C3")), "u");
        assertThat(added.changed()).isTrue();
        assertThat(added.revision()).isEqualTo(2);
        assertThat(storedPlans(added.testCaseVersionId())).containsExactly("A1", "B2", "C3");
        assertThat(service.save("policy-page", "gili-many-plans", withPlans(List.of("A1")), "u").revision()).isEqualTo(3);
    }

    @Test
    void noPlanCodeIsAllowedAndBadOnesAreRejected() {
        assertThat(service.save("policy-page", "gili-no-plan", withPlans(null), "u").changed()).isTrue();

        assertThatThrownBy(() -> service.save("policy-page", "gili-bad-plan", withPlans(List.of("OK1", "bad code!")), "u"))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.errors()).anyMatch(m -> m.contains("'bad code!'")));
    }

    @Test
    void failureStatusesAndFailureJourneyStepsCannotBeExpected() {
        String negative = "{\"incomingEvent\":{\"status\":\"TECHNICAL_FAILURE\"},"
                + "\"targets\":[{\"role\":\"OWNER\",\"status\":\"FAILED\"}],\"journey\":[\"requestInvalid\"]}";

        assertThatThrownBy(() -> service.save("policy-page", "gili-negative", request("Neg", negative), "u"))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors()).contains(
                        "incomingEvent.status must be a success status, not TECHNICAL_FAILURE",
                        "targets[OWNER].status must be a success status",
                        "journey step requestInvalid is a failure and cannot be expected"));
    }
}
