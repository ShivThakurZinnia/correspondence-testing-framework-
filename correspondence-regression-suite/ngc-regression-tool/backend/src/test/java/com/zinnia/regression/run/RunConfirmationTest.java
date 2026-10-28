package com.zinnia.regression.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import com.zinnia.regression.testcase.ValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Large manual runs ask for confirmation; nothing ever refuses a run because of its size. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:confirm-test;DB_CLOSE_DELAY=-1",
        "regression.run.large-run-threshold=5", "regression.run.events-per-second=10"})
class RunConfirmationTest {

    static final String PAYLOAD = """
            {"id":"a","source":"zahara","specversion":"1","type":"policy.activation.completed",
             "correlationid":"11111111-2222-4333-8444-555555555555","carrier":"GILI",
             "identifiers":[{"identifier":"planCode","value":"GCFIA902"},{"identifier":"policyNumber","value":"%s"}]}
            """;

    @Autowired RunService runs;
    @Autowired TestCaseService cases;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        for (String t : List.of("test_run_case", "test_run", "module_version_case", "module_version",
                "test_case_version_tag", "test_case_version_plan", "test_case_version", "test_case")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
    }

    void cases(int n) {
        for (int i = 0; i < n; i++) {
            long payload = artifacts.saveInDb("EVENT_PAYLOAD", "p" + i, "application/json", PAYLOAD.formatted("POL" + i), "t");
            cases.save("policy-page", "case-" + String.format("%04d", i), new TestCaseRequest("c" + i, null, "SOR",
                    "zinnia.correspondence.sor", "policy.activation.completed", "GILI", null, payload, null,
                    "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}", 600, true, List.of("client:GILI"), "n"), "t");
        }
    }

    @Test
    void previewSaysHowManyEventsAndHowLongAtLeast() {
        cases(6);

        var p = runs.preview(new RunRequest("policy-page", null, "qa", null, "cli", null, 2.0, null));

        assertThat(p.events()).isEqualTo(6);
        assertThat(p.cases()).isEqualTo(6);
        assertThat(p.minimumSeconds()).as("6 events at 2/s").isEqualTo(3);
        assertThat(p.confirmAbove()).isEqualTo(5);
        assertThat(p.requiresConfirmation()).isTrue();
    }

    @Test
    void aLargeManualRunNeedsTheCountRepeatedBackAndASmallOneDoesNot() {
        cases(6);

        assertThatThrownBy(() -> runs.create(new RunRequest("policy-page", null, "qa", null, "ui"), "t"))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.errors().get(0)).contains("6 events").contains("confirmEvents=6"));
        assertThatThrownBy(() -> runs.create(new RunRequest("policy-page", null, "qa", null, "ui", null, null, 5), "t"))
                .isInstanceOf(ValidationException.class);
        assertThat(runs.create(new RunRequest("policy-page", null, "qa", null, "ui", null, null, 6), "t")).isNotBlank();
        assertThat(runs.create(new RunRequest("policy-page", null, "qa", "client:GILI", "SCHEDULE"), "scheduler"))
                .as("schedules are set up on purpose and never ask").isNotBlank();
    }

    @Test
    void thereIsNoMaximumRunSize() {
        cases(40);

        assertThat(runs.preview(new RunRequest("policy-page", null, "qa", null, "cli")).events()).isEqualTo(40);
        assertThat(runs.create(new RunRequest("policy-page", null, "qa", null, "cli", null, null, 40), "t")).isNotBlank();
        assertThat(jdbc.sql("SELECT total_cases FROM test_run").query(Integer.class).single()).isEqualTo(40);
    }
}
