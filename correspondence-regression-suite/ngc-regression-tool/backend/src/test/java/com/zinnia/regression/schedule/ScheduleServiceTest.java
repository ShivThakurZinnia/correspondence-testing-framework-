package com.zinnia.regression.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.payload.PayloadTest;
import com.zinnia.regression.run.RunExecutor;
import com.zinnia.regression.testcase.ConflictException;
import com.zinnia.regression.testcase.SaveResult;
import com.zinnia.regression.testcase.TestCaseRequest;
import com.zinnia.regression.testcase.TestCaseService;
import com.zinnia.regression.testcase.ValidationException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sched-test;DB_CLOSE_DELAY=-1")
class ScheduleServiceTest {

    @MockitoBean RunExecutor executor;
    @Autowired ScheduleService schedules;
    @Autowired TestCaseService cases;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    SaveResult saved;

    @BeforeEach
    void seed() {
        Mockito.reset(executor);
        for (String t : List.of("run_observation", "assertion_result", "test_run_case", "test_run", "schedule",
                "module_version_case", "module_version", "test_case_version_tag", "test_case_version_plan", "test_case_version", "test_case",
                "audit_log")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        long payload = artifacts.saveInDb("EVENT_PAYLOAD", "p.json", "application/json", PayloadTest.PAYLOAD, "t");
        saved = cases.save("policy-page", "gili-owner-print", new TestCaseRequest("n", null, "SOR",
                "zinnia.correspondence.sor", "e", "GILI", null, payload, null,
                "{\"incomingEvent\":{\"status\":\"SENT_TO_CDS\"}}", 600, true, List.of("client:GILI"), "x"), "t");
    }

    ScheduleRequest req(String name, String cron, String tz, String overlap) {
        return new ScheduleRequest(name, "policy-page", null, "qa", "client:GILI", cron, tz, overlap, null);
    }

    long createDue(String name, String overlap) {
        long id = schedules.create(req(name, "0 0 6 * * *", "UTC", overlap), "t");
        jdbc.sql("UPDATE schedule SET created_at = TIMESTAMP WITH TIME ZONE '2026-10-01 00:00:00+00' WHERE id = :i")
                .param("i", id).update();
        return id;
    }

    @Test
    void invalidDefinitionsAreRejectedWithClearMessages() {
        ScheduleRequest bad = new ScheduleRequest("", "nope", 9, "mars", "bad", "every day", "Mars/Base", "MAYBE", null);

        assertThatThrownBy(() -> schedules.create(bad, "t")).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.errors()).hasSize(7));
    }

    @Test
    void duplicateNamesAreRejected() {
        schedules.create(req("daily-qa", "0 0 6 * * *", "UTC", null), "t");

        assertThatThrownBy(() -> schedules.create(req("daily-qa", "0 0 6 * * *", "UTC", null), "t"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void dueScheduleRunsTheLatestVersionWithoutAnyApprovalAndFiresOnce() {
        createDue("daily-qa", "SKIP");
        assertThat(jdbc.sql("SELECT status FROM module_version WHERE id = :i").param("i", saved.moduleVersionId())
                .query(String.class).single()).as("nobody approved it").isEqualTo("DRAFT");

        List<String> started = schedules.tick(Instant.parse("2026-10-27T07:00:00Z"));

        assertThat(started).hasSize(1);
        verify(executor).executeAsync(started.get(0));
        assertThat(jdbc.sql("SELECT module_version_id FROM test_run").query(Long.class).single()).isEqualTo(saved.moduleVersionId());
        assertThat(jdbc.sql("SELECT trigger_source || '/' || triggered_by FROM test_run").query(String.class).single())
                .isEqualTo("SCHEDULE/scheduler");
        assertThat(jdbc.sql("SELECT schedule_id IS NOT NULL FROM test_run").query(Boolean.class).single()).isTrue();
        assertThat(schedules.tick(Instant.parse("2026-10-27T07:01:00Z"))).as("same occurrence is not repeated").isEmpty();
    }

    @Test
    void aModuleWithoutTestCasesIsSkippedAndLaterPicksUpTheNewestVersion() {
        jdbc.sql("INSERT INTO test_module (code, name) VALUES ('empty-module', 'Empty')").update();
        long id = schedules.create(new ScheduleRequest("empty-sched", "empty-module", null, "qa", null, "0 0 6 * * *", "UTC", null, null), "t");
        jdbc.sql("UPDATE schedule SET created_at = TIMESTAMP WITH TIME ZONE '2026-10-01 00:00:00+00' WHERE id = :i").param("i", id).update();

        assertThat(schedules.tick(Instant.parse("2026-10-27T07:00:00Z"))).as("nothing to run yet").isEmpty();
        assertThat(count("test_run")).isZero();
    }

    @Test
    void scheduleNotYetDueOrDisabledDoesNothing() {
        long id = createDue("daily-qa", "SKIP");

        assertThat(schedules.tick(Instant.parse("2026-10-01T05:59:00Z"))).isEmpty();
        schedules.setEnabled(id, false);
        assertThat(schedules.tick(Instant.parse("2026-10-27T07:00:00Z"))).isEmpty();
        verify(executor, never()).executeAsync(anyString());
    }

    @Test
    void cronIsEvaluatedInTheScheduleTimezone() {
        long id = schedules.create(req("ist-morning", "0 0 6 * * *", "Asia/Kolkata", null), "t");
        jdbc.sql("UPDATE schedule SET created_at = TIMESTAMP WITH TIME ZONE '2026-10-26 12:00:00+00' WHERE id = :i")
                .param("i", id).update();
        jdbc.sql("UPDATE module_version SET status = 'APPROVED'").update();

        assertThat(schedules.tick(Instant.parse("2026-10-27T00:29:00Z"))).as("06:00 IST is 00:30 UTC").isEmpty();
        assertThat(schedules.tick(Instant.parse("2026-10-27T00:31:00Z"))).hasSize(1);
    }

    @Test
    void overlapPolicySkipDropsTheOccurrenceAndQueueRetriesNextTick() {
        long skip = createDue("skip-one", "SKIP");
        jdbc.sql("UPDATE module_version SET status = 'APPROVED'").update();
        jdbc.sql("""
                INSERT INTO test_run (run_key, module_version_id, environment_id, trigger_source, status, schedule_id)
                VALUES ('busy', :v, (SELECT id FROM environment WHERE code = 'qa'), 'SCHEDULE', 'RUNNING', :s)
                """).param("v", saved.moduleVersionId()).param("s", skip).update();

        assertThat(schedules.tick(Instant.parse("2026-10-27T07:00:00Z"))).isEmpty();
        assertThat(jdbc.sql("SELECT last_fired_at IS NOT NULL FROM schedule WHERE id = :i").param("i", skip)
                .query(Boolean.class).single()).as("skipped occurrence is consumed").isTrue();

        jdbc.sql("UPDATE schedule SET overlap_policy = 'QUEUE', last_fired_at = NULL WHERE id = :i").param("i", skip).update();
        assertThat(schedules.tick(Instant.parse("2026-10-27T07:00:00Z"))).isEmpty();
        assertThat(jdbc.sql("SELECT last_fired_at IS NULL FROM schedule WHERE id = :i").param("i", skip)
                .query(Boolean.class).single()).as("queued occurrence stays pending").isTrue();

        jdbc.sql("UPDATE test_run SET status = 'PASSED'").update();
        assertThat(schedules.tick(Instant.parse("2026-10-27T07:05:00Z"))).hasSize(1);
    }

    @Test
    void listShowsNextFireTime() {
        schedules.create(req("daily-qa", "0 0 6 * * *", "UTC", null), "t");

        assertThat(schedules.list()).singleElement().satisfies(s -> {
            assertThat(s.get("name")).isEqualTo("daily-qa");
            assertThat(s.get("next_fire_at")).isNotNull();
        });
    }

    @Test
    void editChangesFieldsRebasesNextFireAndIsAudited() {
        long id = createDue("daily-qa", "SKIP");

        schedules.update(id, new ScheduleRequest("daily-qa-ist", "policy-page", null, "uat", "client:GILI",
                "0 30 7 * * *", "Asia/Kolkata", "QUEUE", null), "editor");

        assertThat(jdbc.sql("SELECT name || '/' || cron_expression || '/' || timezone || '/' || overlap_policy FROM schedule")
                .query(String.class).single()).isEqualTo("daily-qa-ist/0 30 7 * * */Asia/Kolkata/QUEUE");
        assertThat(jdbc.sql("SELECT e.code FROM schedule s JOIN environment e ON e.id = s.environment_id")
                .query(String.class).single()).isEqualTo("uat");
        assertThat(jdbc.sql("SELECT last_fired_at IS NOT NULL FROM schedule").query(Boolean.class).single())
                .as("old backlog is not replayed after an edit").isTrue();
        assertThat(jdbc.sql("SELECT action || '/' || actor FROM audit_log WHERE entity_type = 'SCHEDULE' AND action = 'UPDATE'")
                .query(String.class).single()).isEqualTo("UPDATE/editor");
    }

    @Test
    void editMayKeepItsOwnNameButNotTakeAnotherSchedulesName() {
        long a = schedules.create(req("a", "0 0 6 * * *", "UTC", null), "t");
        schedules.create(req("b", "0 0 7 * * *", "UTC", null), "t");

        schedules.update(a, req("a", "0 0 8 * * *", "UTC", null), "t");
        assertThatThrownBy(() -> schedules.update(a, req("b", "0 0 8 * * *", "UTC", null), "t"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void cannotEditOrDeleteWhileExecutingButCanOnceFinished() {
        long id = schedules.create(req("daily-qa", "0 0 6 * * *", "UTC", null), "t");
        jdbc.sql("""
                INSERT INTO test_run (run_key, module_version_id, environment_id, trigger_source, status, schedule_id)
                VALUES ('live', :v, (SELECT id FROM environment WHERE code = 'qa'), 'SCHEDULE', 'RUNNING', :s)
                """).param("v", saved.moduleVersionId()).param("s", id).update();

        assertThatThrownBy(() -> schedules.update(id, req("x", "0 0 6 * * *", "UTC", null), "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("executing");
        assertThatThrownBy(() -> schedules.delete(id, "t")).isInstanceOf(ConflictException.class);
        assertThat(schedules.list().get(0).get("executing")).isEqualTo(true);

        jdbc.sql("UPDATE test_run SET status = 'PASSED'").update();
        schedules.update(id, req("x", "0 0 6 * * *", "UTC", null), "t");
        schedules.delete(id, "t");
        assertThat(count("schedule")).isZero();
    }

    @Test
    void deleteKeepsRunHistoryWithTheScheduleName() {
        long id = createDue("daily-qa", "SKIP");
        jdbc.sql("UPDATE module_version SET status = 'APPROVED'").update();
        schedules.tick(Instant.parse("2026-10-27T07:00:00Z"));
        jdbc.sql("UPDATE test_run SET status = 'PASSED'").update();

        schedules.delete(id, "t");

        assertThat(jdbc.sql("SELECT schedule_name FROM test_run").query(String.class).single()).isEqualTo("daily-qa");
        assertThat(jdbc.sql("SELECT schedule_id IS NULL FROM test_run").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'DELETE'").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void unknownScheduleIsRejected() {
        assertThatThrownBy(() -> schedules.delete(999, "t")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> schedules.update(999, req("x", "0 0 6 * * *", "UTC", null), "t"))
                .isInstanceOf(ValidationException.class);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void runNowStartsARunOwnedByTheScheduleAndIsRefusedWhileItExecutes() {
        long id = schedules.create(req("daily-qa", "0 0 6 * * *", "UTC", null), "t");

        String key = schedules.runNow(id, "alice");

        verify(executor).executeAsync(key);
        assertThat(jdbc.sql("SELECT trigger_source || '/' || triggered_by FROM test_run WHERE run_key = :k")
                .param("k", key).query(String.class).single()).isEqualTo("SCHEDULE/alice");
        assertThat(jdbc.sql("SELECT schedule_id FROM test_run WHERE run_key = :k").param("k", key).query(Long.class).single())
                .isEqualTo(id);
        assertThat(jdbc.sql("SELECT last_fired_at IS NULL FROM schedule WHERE id = :i").param("i", id)
                .query(Boolean.class).single()).as("cron times are not shifted").isTrue();
        assertThatThrownBy(() -> schedules.runNow(id, "alice")).isInstanceOf(ConflictException.class);
    }

    @Test
    void runNowRejectsUnknownScheduleAndModuleWithoutTests() {
        assertThatThrownBy(() -> schedules.runNow(9999, "t")).isInstanceOf(ValidationException.class);
    }

    @Test
    void aScheduleWithoutAModuleRunsEveryModulesMatchingTests() {
        long id = schedules.create(new ScheduleRequest("all-gili", null, null, "qa", "client:GILI", "0 0 6 * * *", "UTC", null, null), "t");

        String key = schedules.runNow(id, "alice");

        assertThat(jdbc.sql("SELECT module_version_id IS NULL FROM test_run WHERE run_key = :k").param("k", key)
                .query(Boolean.class).single()).isTrue();
        assertThat(schedules.list().get(0).get("module")).isNull();
    }
}
