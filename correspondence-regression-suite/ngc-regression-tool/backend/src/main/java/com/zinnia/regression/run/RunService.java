package com.zinnia.regression.run;

import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.common.Keys;
import com.zinnia.regression.config.WiredEnvironment;
import com.zinnia.regression.payload.EventPayloadValidator;
import com.zinnia.regression.testcase.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a run: selects the cases by tag, registers a fresh correlationId for each. */
@Service
public class RunService {

    private final JdbcClient jdbc;
    private final WiredEnvironment wired;
    private final ArtifactRepository artifacts;
    private final EventPayloadValidator payloads;
    private final int largeRunThreshold;
    private final double defaultEps;

    public RunService(JdbcClient jdbc, WiredEnvironment wired, ArtifactRepository artifacts, EventPayloadValidator payloads,
                      @Value("${regression.run.large-run-threshold:100}") int largeRunThreshold,
                      @Value("${regression.run.events-per-second:5}") double defaultEps) {
        this.jdbc = jdbc;
        this.wired = wired;
        this.artifacts = artifacts;
        this.payloads = payloads;
        this.largeRunThreshold = largeRunThreshold;
        this.defaultEps = defaultEps;
    }

    public String create(RunRequest req, String user) {
        return create(req, user, null);
    }

    /** What a run would send, before anything is created. */
    public record Preview(int cases, int events, int minimumSeconds, int confirmAbove, boolean requiresConfirmation) {
    }

    private record Play(long caseVersionId, String planCode, String serialKey) {
    }

    private record Selection(long envId, Long versionId, List<Play> plays) {
    }

    public Preview preview(RunRequest req) {
        Selection sel = select(req);
        int events = sel.plays().size();
        double eps = req.eventsPerSecond() != null && req.eventsPerSecond() > 0 ? req.eventsPerSecond() : defaultEps;
        return new Preview((int) sel.plays().stream().map(Play::caseVersionId).distinct().count(), events,
                (int) Math.ceil(events / eps), largeRunThreshold, events > largeRunThreshold);
    }

    @Transactional
    public String create(RunRequest req, String user, Long scheduleId) {
        Selection sel = select(req);
        int events = sel.plays().size();
        boolean manual = req.trigger() == null || !req.trigger().equalsIgnoreCase("SCHEDULE");
        if (manual && events > largeRunThreshold && !Integer.valueOf(events).equals(req.confirmEvents())) {
            throw new ValidationException(List.of("This run would send " + events + " events to " + req.env()
                    + ". Confirm by repeating the count (confirmEvents=" + events + ")."));
        }
        return insertRun(sel, req, user, scheduleId, null);
    }

    /** A new run containing only the events that did not pass (failed, timed out or errored) in an earlier run. */
    @Transactional
    public String rerunFailed(String runKey, String user) {
        Object[] run = jdbc.sql("SELECT id, module_version_id, environment_id FROM test_run WHERE run_key = :k").param("k", runKey)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getObject(2) == null ? null : rs.getLong(2), rs.getLong(3)}).optional()
                .orElseThrow(() -> new ValidationException(List.of("Unknown run " + runKey)));
        List<Play> plays = jdbc.sql("""
                SELECT test_case_version_id, plan_code, serial_key FROM test_run_case
                WHERE run_id = :r AND status IN ('FAILED','TIMEOUT','ERROR') ORDER BY id
                """).param("r", (Long) run[0]).query((rs, n) -> new Play(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
        if (plays.isEmpty()) {
            throw new ValidationException(List.of("Nothing to re-run: no failed, timed-out or errored events in this run"));
        }
        String env = jdbc.sql("SELECT code FROM environment WHERE id = :i").param("i", (Long) run[2]).query(String.class).single();
        if (!wired.allows(env)) {
            throw new ValidationException(List.of("This instance is connected to " + wired.code().orElse("?") + ", not " + env));
        }
        Selection sel = new Selection((Long) run[2], (Long) run[1], plays);
        return insertRun(sel, new RunRequest(null, null, env, null, "UI"), user, null, (Long) run[0]);
    }

    @Transactional
    public void cancel(String runKey) {
        int updated = jdbc.sql("UPDATE test_run SET cancel_requested = TRUE WHERE run_key = :k AND status IN ('QUEUED','RUNNING')")
                .param("k", runKey).update();
        if (updated == 0) {
            throw new ValidationException(List.of("Run " + runKey + " is not running, so it cannot be cancelled"));
        }
    }

    private Selection select(RunRequest req) {
        List<String> errors = new ArrayList<>();
        Long env = jdbc.sql("SELECT id FROM environment WHERE code = :c AND active = TRUE").param("c", req.env())
                .query(Long.class).optional().orElse(null);
        if (env == null) {
            errors.add("Unknown or inactive environment: " + req.env());
        } else if (!wired.allows(req.env())) {
            errors.add("This instance is connected to " + wired.code().orElse("?") + ", not " + req.env());
        }
        boolean oneModule = req.module() != null && !req.module().isBlank();
        long[] version = oneModule ? jdbc.sql("""
                SELECT v.id, v.version_no FROM module_version v JOIN test_module m ON m.id = v.module_id
                WHERE m.code = :m AND (:n IS NULL OR v.version_no = :n)
                ORDER BY v.version_no DESC LIMIT 1
                """).param("m", req.module()).param("n", req.versionNo())
                .query((rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}).optional().orElse(null) : null;
        if (oneModule && version == null) {
            errors.add("No matching version for module " + req.module());
        }
        // Tags of the same kind are alternatives (mapper:anniversary,mapper:new-business = either mapper); different
        // kinds all have to match (client:GILI plus a mapper = GILI cases of those mappers).
        java.util.Map<String, List<String>> tags = new java.util.LinkedHashMap<>();
        for (String t : req.tags() == null || req.tags().isBlank() ? new String[0] : req.tags().split(",")) {
            String[] parts = t.trim().split(":", 2);
            if (parts.length != 2) {
                errors.add("Bad tag expression: " + t);
            } else {
                tags.computeIfAbsent(parts[0].toUpperCase(), k -> new ArrayList<>()).add(parts[1]);
            }
        }
        if (req.maxInFlight() != null && req.maxInFlight() < 1) {
            errors.add("maxInFlight must be at least 1");
        }
        if (req.eventsPerSecond() != null && req.eventsPerSecond() <= 0) {
            errors.add("eventsPerSecond must be greater than 0");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        // one module (CLI, module page) or, by default, the newest version of every module
        List<Long> cases = jdbc.sql("""
                SELECT v.id FROM module_version_case mc JOIN test_case_version v ON v.id = mc.test_case_version_id
                JOIN module_version mv ON mv.id = mc.module_version_id
                WHERE v.enabled = TRUE
                  AND mv.id = CASE WHEN :one THEN :mv ELSE
                    (SELECT x.id FROM module_version x WHERE x.module_id = mv.module_id ORDER BY x.version_no DESC LIMIT 1) END
                ORDER BY v.id
                """).param("one", oneModule).param("mv", version == null ? null : version[0])
                .query(Long.class).list();
        List<Play> plays = new ArrayList<>();
        for (Long id : cases) {
            boolean all = true;
            for (var kind : tags.entrySet()) {
                all &= jdbc.sql("""
                        SELECT COUNT(*) FROM test_case_version_tag vt JOIN tag g ON g.id = vt.tag_id
                        WHERE vt.test_case_version_id = :v AND g.category = :c AND g.code IN (:codes)
                        """).param("v", id).param("c", kind.getKey()).param("codes", kind.getValue())
                        .query(Integer.class).single() > 0;
            }
            if (!all) {
                continue;
            }
            // one event per plan code: the same payload and expected outcome, played once for each code
            var info = payloadInfo(id);
            List<String> codes = jdbc.sql("SELECT plan_code FROM test_case_version_plan WHERE test_case_version_id = :v ORDER BY position")
                    .param("v", id).query(String.class).list();
            if (codes.isEmpty()) {
                plays.add(new Play(id, null, serialKey(info.policyNumber(), info.planCode())));
            }
            codes.forEach(c -> plays.add(new Play(id, c, serialKey(info.policyNumber(), c))));
        }
        if (plays.isEmpty()) {
            throw new ValidationException(List.of("No enabled test cases match the selection"));
        }
        return new Selection(env, version == null ? null : version[0], plays);
    }

    private com.zinnia.regression.payload.PayloadInfo payloadInfo(long caseVersionId) {
        Long artifact = jdbc.sql("SELECT payload_artifact_id FROM test_case_version WHERE id = :v").param("v", caseVersionId)
                .query(Long.class).single();
        return payloads.validate(artifacts.findContent(artifact));
    }

    /**
     * Two events for the same policy and plan never run at the same time. Plan-code variants of one payload are
     * different keys, so they run side by side. No policy number in the payload = no ordering needed.
     */
    private static String serialKey(String policyNumber, String planCode) {
        return policyNumber == null ? null : policyNumber + "|" + (planCode == null ? "" : planCode);
    }

    private String insertRun(Selection sel, RunRequest req, String user, Long scheduleId, Long rerunOf) {
        String runKey = UUID.randomUUID().toString();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO test_run (run_key, module_version_id, environment_id, trigger_source, triggered_by,
                  tag_expression, status, total_cases, schedule_id, schedule_name, max_in_flight, events_per_second,
                  rerun_of_run_id)
                VALUES (:k, :mv, :env, :trigger, :user, :tags, 'QUEUED', :total, :schedule,
                  (SELECT name FROM schedule WHERE id = :schedule), :mif, :eps, :rerun)
                """).param("k", runKey).param("mv", sel.versionId()).param("env", sel.envId())
                .param("trigger", req.trigger() == null ? "UI" : req.trigger().toUpperCase()).param("user", user)
                .param("tags", req.tags()).param("total", sel.plays().size()).param("schedule", scheduleId)
                .param("mif", req.maxInFlight()).param("eps", req.eventsPerSecond()).param("rerun", rerunOf).update(keys);
        long runId = Keys.idOf(keys);
        for (Play play : sel.plays()) {
            jdbc.sql("""
                    INSERT INTO test_run_case (run_id, test_case_version_id, correlation_id, topic_name, status, plan_code, serial_key)
                    VALUES (:r, :v, :c, '', 'PENDING', :plan, :sk)
                    """).param("r", runId).param("v", play.caseVersionId()).param("c", UUID.randomUUID().toString())
                    .param("plan", play.planCode()).param("sk", play.serialKey()).update();
        }
        return runKey;
    }
}
