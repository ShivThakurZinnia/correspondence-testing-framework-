package com.zinnia.regression.run;

import com.zinnia.regression.common.Rows;
import com.zinnia.regression.config.WiredEnvironment;
import com.zinnia.regression.schedule.ScheduleService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only views behind the build-server style UI: overview, run list/history, per-event progress. */
@RestController
@RequestMapping("/api")
public class RunQueryController {

    /** Ordered journey of one event. A stage counts as reached when an observation matches one of its markers. */
    static final List<Stage> STAGES = List.of(
            new Stage("INJECTED", "Published to Kafka", List.of("KAFKA:published")),
            new Stage("NGC_RECEIVED", "NGC received", List.of("incoming_events:")),
            new Stage("XML_GENERATED", "XML generated", List.of("target_role_and_event_status_audit:XML Generated")),
            new Stage("SENT_TO_CDS", "Sent to CDS",
                    List.of("cds_events:", "event_journey:correspondence.generation.requestSentToCDS")),
            new Stage("PDF_STARTED", "PDF started", List.of("internal_events:pdf.generation.started",
                    "event_journey:pdf.generation.started")),
            new Stage("PDF_COMPLETED", "PDF completed", List.of("internal_events:pdf.generation.completed",
                    "event_journey:pdf.generation.completed")),
            new Stage("DELIVERY", "Delivery update", List.of("internal_events:delivery.", "event_journey:delivery.")));

    record Stage(String code, String label, List<String> markers) {
    }

    private final JdbcClient jdbc;
    private final ScheduleService schedules;
    private final WiredEnvironment wired;

    public RunQueryController(JdbcClient jdbc, ScheduleService schedules, WiredEnvironment wired) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.wired = wired;
    }

    /** One tile per environment and scope (the tags a run selected; none = everything): latest result, running count, 7-day pass rate. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        String same = "r.environment_id = e.id AND COALESCE(r.tag_expression, '') = g.scope";
        List<Map<String, Object>> tiles = jdbc.sql("""
                SELECT e.code AS env, NULLIF(g.scope, '') AS scope,
                  (SELECT r.run_key FROM test_run r WHERE %1$s AND r.status IN ('PASSED','FAILED','ERROR')
                    ORDER BY r.queued_at DESC LIMIT 1) AS last_run_key,
                  (SELECT r.status FROM test_run r WHERE %1$s AND r.status IN ('PASSED','FAILED','ERROR')
                    ORDER BY r.queued_at DESC LIMIT 1) AS last_status,
                  (SELECT COUNT(*) FROM test_run r WHERE %1$s AND r.status IN ('QUEUED','RUNNING')) AS active_runs,
                  (SELECT COUNT(*) FROM test_run r WHERE %1$s AND r.status IN ('PASSED','FAILED','ERROR')
                      AND r.queued_at > DATEADD('DAY', -7, CURRENT_TIMESTAMP)) AS runs_7d,
                  (SELECT COUNT(*) FROM test_run r WHERE %1$s AND r.status = 'PASSED'
                      AND r.queued_at > DATEADD('DAY', -7, CURRENT_TIMESTAMP)) AS passed_7d
                FROM environment e
                JOIN (SELECT DISTINCT environment_id AS env_id, COALESCE(tag_expression, '') AS scope FROM test_run) g ON g.env_id = e.id
                WHERE e.active = TRUE AND (:w IS NULL OR e.code = :w)
                ORDER BY e.code, g.scope
                """.formatted(same)).param("w", wired.code().orElse(null)).query().listOfRows().stream().map(Rows::lower).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tiles", tiles);
        out.put("running", runs(null, null, "RUNNING", null, 0, 20));
        out.put("recent", runs(null, null, null, null, 0, 10));
        out.put("upcoming", schedules.list().stream().filter(s -> Boolean.TRUE.equals(s.get("enabled")))
                .filter(s -> s.get("next_fire_at") != null)
                .sorted(java.util.Comparator.comparing(s -> s.get("next_fire_at").toString()))
                .limit(10).map(s -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    for (String k : List.of("id", "name", "env", "tag_expression", "cron_expression",
                            "timezone", "next_fire_at", "last_fired_at", "executing")) {
                        u.put(k, s.get(k));
                    }
                    return u;
                }).toList());
        return out;
    }

    /** Build history with filters; newest first. */
    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String env,
                                          @RequestParam(required = false) String module,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String trigger,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "25") int size) {
        return jdbc.sql("""
                SELECT r.run_key, r.status, r.trigger_source, r.triggered_by, e.code AS env, m.code AS module,
                       v.version AS module_version, r.tag_expression, r.queued_at, r.started_at, r.finished_at,
                       r.total_cases, r.passed_cases, r.failed_cases, r.ngc_build_version, r.rule_set_version,
                       COALESCE(s.name, r.schedule_name) AS schedule_name,
                       DATEDIFF('SECOND', r.started_at, COALESCE(r.finished_at, CURRENT_TIMESTAMP)) AS duration_seconds
                FROM test_run r
                JOIN environment e ON e.id = r.environment_id
                LEFT JOIN module_version v ON v.id = r.module_version_id
                LEFT JOIN test_module m ON m.id = v.module_id
                LEFT JOIN schedule s ON s.id = r.schedule_id
                WHERE (:env IS NULL OR e.code = :env) AND (:module IS NULL OR m.code = :module)
                  AND (:status IS NULL OR r.status = UPPER(:status)) AND (:trigger IS NULL OR r.trigger_source = UPPER(:trigger))
                ORDER BY r.queued_at DESC, r.id DESC LIMIT :size OFFSET :offset
                """)
                .param("env", env).param("module", module).param("status", status).param("trigger", trigger)
                .param("size", Math.min(size, 200)).param("offset", Math.max(page, 0) * Math.min(size, 200))
                .query().listOfRows().stream().map(Rows::lower).toList();
    }

    private static final List<String> STATUS_ORDER = List.of("ERROR", "FAILED", "TIMEOUT", "RUNNING", "PENDING", "SKIPPED", "PASSED");

    /**
     * Run header, counters by status, and one page of events (failures first). Bulk runs have thousands of events, so
     * the list is filtered and paged: status=FAILED,TIMEOUT,ERROR, q=text (scenario, plan code, correlation id).
     */
    @GetMapping("/runs/{runKey}")
    public Map<String, Object> run(@PathVariable String runKey,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        Map<String, Object> run = jdbc.sql("""
                SELECT r.id, r.run_key, r.status, r.trigger_source, r.triggered_by, e.code AS env, m.code AS module,
                       v.version AS module_version, r.tag_expression, r.queued_at, r.started_at, r.finished_at, r.total_cases,
                       r.passed_cases, r.failed_cases, r.ngc_build_version, r.rule_set_version, r.max_in_flight,
                       r.events_per_second, r.cancel_requested, r.details_purged,
                       (SELECT x.run_key FROM test_run x WHERE x.id = r.rerun_of_run_id) AS rerun_of
                FROM test_run r JOIN environment e ON e.id = r.environment_id
                LEFT JOIN module_version v ON v.id = r.module_version_id LEFT JOIN test_module m ON m.id = v.module_id
                WHERE r.run_key = :k
                """).param("k", runKey).query().listOfRows().stream().map(Rows::lower).findFirst().orElse(null);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run " + runKey);
        }
        long id = ((Number) run.get("id")).longValue();
        Map<String, Object> summary = new LinkedHashMap<>();
        STATUS_ORDER.forEach(st -> summary.put(st, 0L));
        jdbc.sql("SELECT status, COUNT(*) FROM test_run_case WHERE run_id = :r GROUP BY status").param("r", id)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list().forEach(e -> summary.put(e.getKey(), e.getValue()));

        List<String> statuses = status == null || status.isBlank() ? null
                : java.util.Arrays.stream(status.split(",")).map(String::trim).map(String::toUpperCase).toList();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        int pageSize = Math.max(1, Math.min(size, 500));
        var spec = jdbc.sql(caseSql() + """
                 WHERE rc.run_id = :id AND (:noStatus = TRUE OR rc.status IN (:statuses))
                   AND (:like IS NULL OR LOWER(c.scenario_key) LIKE :like OR LOWER(tcv.name) LIKE :like
                        OR LOWER(rc.plan_code) LIKE :like OR rc.correlation_id LIKE :like)
                 ORDER BY CASE rc.status WHEN 'ERROR' THEN 0 WHEN 'FAILED' THEN 1 WHEN 'TIMEOUT' THEN 2 WHEN 'RUNNING' THEN 3
                   WHEN 'PENDING' THEN 4 WHEN 'SKIPPED' THEN 5 ELSE 6 END, rc.id
                 LIMIT :size OFFSET :offset
                """).param("id", id).param("noStatus", statuses == null)
                .param("statuses", statuses == null ? List.of("-") : statuses).param("like", like)
                .param("size", pageSize).param("offset", (long) Math.max(page, 0) * pageSize);
        List<Map<String, Object>> cases = new ArrayList<>(Rows.list(spec));
        cases.forEach(c -> c.put("progress", progress(((Number) c.get("run_case_id")).longValue(), c.get("published_at") != null)));
        long matching = jdbc.sql("""
                SELECT COUNT(*) FROM test_run_case rc JOIN test_case_version tcv ON tcv.id = rc.test_case_version_id
                JOIN test_case c ON c.id = tcv.test_case_id
                WHERE rc.run_id = :id AND (:noStatus = TRUE OR rc.status IN (:statuses))
                  AND (:like IS NULL OR LOWER(c.scenario_key) LIKE :like OR LOWER(tcv.name) LIKE :like
                       OR LOWER(rc.plan_code) LIKE :like OR rc.correlation_id LIKE :like)
                """).param("id", id).param("noStatus", statuses == null).param("statuses", statuses == null ? List.of("-") : statuses)
                .param("like", like).query(Long.class).single();

        Map<String, Object> out = new LinkedHashMap<>(run);
        out.remove("id");
        out.put("summary", summary);
        out.put("page", Math.max(page, 0));
        out.put("size", pageSize);
        out.put("matching", matching);
        out.put("cases", cases);
        return out;
    }

    /**
     * What changed since the previous finished run of the same scope (tags) and environment: events that passed then and fail
     * now (new failures), the reverse (fixed), and events failing in both. This is the headline of a scheduled bulk run.
     */
    @GetMapping("/runs/{runKey}/compare")
    public Map<String, Object> compare(@PathVariable String runKey) {
        Object[] cur = jdbc.sql("""
                SELECT r.id, r.tag_expression, r.environment_id FROM test_run r WHERE r.run_key = :k
                """).param("k", runKey).query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3)}).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run " + runKey));
        Map<String, Object> prev = Rows.list(jdbc.sql("""
                SELECT p.id, p.run_key, p.finished_at FROM test_run p
                WHERE p.id < :cur AND p.environment_id = :env AND p.status IN ('PASSED','FAILED','ERROR')
                  AND COALESCE(p.tag_expression, '') = COALESCE(:tags, '')
                ORDER BY p.id DESC LIMIT 1
                """).param("cur", (Long) cur[0]).param("env", (Long) cur[2]).param("tags", (String) cur[1])).stream().findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (prev == null) {
            out.put("previous", null);
            return out;
        }
        record Row(String key, String scenario, String plan, String now, String before, String correlation) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT c.scenario_key, cur.plan_code, cur.status, prv.status, cur.correlation_id
                FROM test_run_case cur
                JOIN test_case_version cv ON cv.id = cur.test_case_version_id JOIN test_case c ON c.id = cv.test_case_id
                LEFT JOIN (SELECT c2.id AS tc, rc2.plan_code AS pc, rc2.status AS status FROM test_run_case rc2
                           JOIN test_case_version v2 ON v2.id = rc2.test_case_version_id JOIN test_case c2 ON c2.id = v2.test_case_id
                           WHERE rc2.run_id = :prev) prv
                  ON prv.tc = c.id AND COALESCE(prv.pc, '') = COALESCE(cur.plan_code, '')
                WHERE cur.run_id = :cur
                """).param("cur", (Long) cur[0]).param("prev", ((Number) prev.get("id")).longValue())
                .query((rs, n) -> new Row(null, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).list();
        java.util.function.Predicate<String> bad = st -> st != null && List.of("FAILED", "TIMEOUT", "ERROR").contains(st);
        List<Map<String, Object>> newFailures = new ArrayList<>();
        List<Map<String, Object>> fixed = new ArrayList<>();
        long stillFailing = 0;
        long newCases = 0;
        long newFailureCount = 0;
        long fixedCount = 0;
        for (Row r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scenario_key", r.scenario());
            item.put("plan_code", r.plan());
            item.put("status", r.now());
            item.put("previous_status", r.before());
            item.put("correlation_id", r.correlation());
            if (r.before() == null) {
                newCases++;
            } else if (bad.test(r.now()) && r.before().equals("PASSED")) {
                newFailureCount++;
                if (newFailures.size() < 200) {
                    newFailures.add(item);
                }
            } else if (r.now().equals("PASSED") && bad.test(r.before())) {
                fixedCount++;
                if (fixed.size() < 200) {
                    fixed.add(item);
                }
            } else if (bad.test(r.now()) && bad.test(r.before())) {
                stillFailing++;
            }
        }
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("run_key", prev.get("run_key"));
        previous.put("finished_at", prev.get("finished_at"));
        out.put("previous", previous);
        out.put("new_failures", newFailureCount);
        out.put("fixed", fixedCount);
        out.put("still_failing", stillFailing);
        out.put("new_cases", newCases);
        out.put("new_failure_events", newFailures);
        out.put("fixed_events", fixed);
        return out;
    }

    /** Everything known about one event: assertions, ordered timeline, artifacts, stage progress. */
    @GetMapping("/events/{correlationId}")
    public Map<String, Object> event(@PathVariable String correlationId) {
        Map<String, Object> header = jdbc.sql(caseSql() + " WHERE rc.correlation_id = :c")
                .param("c", correlationId).query().listOfRows().stream().map(Rows::lower).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown correlationId"));
        long id = ((Number) header.get("run_case_id")).longValue();
        Map<String, Object> out = new LinkedHashMap<>(header);
        out.put("progress", progress(id, header.get("published_at") != null));
        out.put("assertions", jdbc.sql("""
                SELECT category, path, expected, actual, passed, message FROM assertion_result
                WHERE run_case_id = :i ORDER BY passed, id""").param("i", id).query().listOfRows().stream().map(Rows::lower).toList());
        out.put("timeline", jdbc.sql("""
                SELECT occurred_at, source, collection, event_type, status, target_role, tango_job_id, role_tracking_id
                FROM run_observation WHERE run_case_id = :i ORDER BY occurred_at, id""").param("i", id).query().listOfRows().stream().map(Rows::lower).toList());
        out.put("artifacts", jdbc.sql("""
                SELECT a.id, a.kind, a.name, ra.role FROM run_case_artifact ra JOIN artifact a ON a.id = ra.artifact_id
                WHERE ra.run_case_id = :i""").param("i", id).query().listOfRows().stream().map(Rows::lower).toList());
        return out;
    }

    /** Historic events across all runs; filter by correlationId fragment, scenario, status, tag or env. */
    @GetMapping("/events")
    public List<Map<String, Object>> events(@RequestParam(required = false) String correlationId,
                                            @RequestParam(required = false) String scenario,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String env,
                                            @RequestParam(required = false) String tag,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        String[] t = tag == null ? new String[0] : tag.split(":", 2);
        return jdbc.sql(caseSql() + """
                 WHERE (:corr IS NULL OR rc.correlation_id LIKE :corrLike)
                   AND (:scenario IS NULL OR c.scenario_key = :scenario)
                   AND (:status IS NULL OR rc.status = UPPER(:status))
                   AND (:env IS NULL OR e.code = :env)
                   AND (:tagCat IS NULL OR EXISTS (SELECT 1 FROM test_case_version_tag vt JOIN tag g ON g.id = vt.tag_id
                        WHERE vt.test_case_version_id = tcv.id AND g.category = UPPER(:tagCat) AND g.code = :tagCode))
                 ORDER BY rc.id DESC LIMIT :size OFFSET :offset
                """)
                .param("corr", correlationId).param("corrLike", "%" + correlationId + "%")
                .param("scenario", scenario).param("status", status).param("env", env)
                .param("tagCat", t.length == 2 ? t[0] : null).param("tagCode", t.length == 2 ? t[1] : null)
                .param("size", Math.min(size, 200)).param("offset", Math.max(page, 0) * Math.min(size, 200))
                .query().listOfRows().stream().map(Rows::lower).toList();
    }

    private static String caseSql() {
        return """
                SELECT rc.id AS run_case_id, rc.correlation_id, rc.ngc_unique_id, rc.status, rc.final_incoming_status,
                       rc.failure_summary, rc.topic_name, rc.published_at, rc.started_at, rc.finished_at,
                       rc.plan_code, c.scenario_key, tcv.name AS scenario_name, tcv.revision, tcv.carrier, tcv.event_type,
                       r.run_key, e.code AS env
                FROM test_run_case rc
                JOIN test_run r ON r.id = rc.run_id
                JOIN environment e ON e.id = r.environment_id
                JOIN test_case_version tcv ON tcv.id = rc.test_case_version_id
                JOIN test_case c ON c.id = tcv.test_case_id
                """;
    }

    private List<Map<String, Object>> progress(long runCaseId, boolean published) {
        List<String> seen = jdbc.sql("""
                SELECT COALESCE(collection, source) || ':' || COALESCE(event_type, '') FROM run_observation
                WHERE run_case_id = :i""").param("i", runCaseId).query(String.class).list();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Stage s : STAGES) {
            boolean reached = s.code().equals("INJECTED")
                    ? published
                    : seen.stream().anyMatch(o -> s.markers().stream().anyMatch(o::startsWith));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.code());
            m.put("label", s.label());
            m.put("reached", reached);
            stages.add(m);
        }
        return stages;
    }
}
