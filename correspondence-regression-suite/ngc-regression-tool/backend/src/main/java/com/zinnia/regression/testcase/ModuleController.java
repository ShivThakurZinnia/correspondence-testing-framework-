package com.zinnia.regression.testcase;

import com.zinnia.regression.common.Rows;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private final TestCaseService service;
    private final JdbcClient jdbc;

    public ModuleController(TestCaseService service, JdbcClient jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> modules() {
        return Rows.list(jdbc.sql("SELECT code, name, description FROM test_module ORDER BY code"));
    }

    @GetMapping("/{module}/versions")
    public List<Map<String, Object>> versions(@PathVariable String module) {
        return Rows.list(jdbc.sql("""
                SELECT v.id, v.version_no, v.version, v.status, v.change_note, v.created_by, v.created_at,
                       (SELECT COUNT(*) FROM module_version_case c WHERE c.module_version_id = v.id) AS case_count
                FROM module_version v JOIN test_module m ON m.id = v.module_id
                WHERE m.code = :m ORDER BY v.version_no DESC
                """).param("m", module));
    }

    private static final String CASES_SELECT = """
            SELECT m.code AS module, c.scenario_key, v.revision, v.name, v.kafka_kind, v.kafka_topic_pattern, v.event_type,
                   v.carrier, v.enabled, v.expected_json,
                   (SELECT LISTAGG(pc.plan_code, ',') WITHIN GROUP (ORDER BY pc.position)
                    FROM test_case_version_plan pc WHERE pc.test_case_version_id = v.id) AS plan_codes,
                   (SELECT LISTAGG(LOWER(t.category) || ':' || t.code, ',') WITHIN GROUP (ORDER BY t.category, t.code)
                    FROM test_case_version_tag vt JOIN tag t ON t.id = vt.tag_id
                    WHERE vt.test_case_version_id = v.id) AS tags
            FROM module_version mv
            JOIN test_module m ON m.id = mv.module_id
            JOIN module_version_case mc ON mc.module_version_id = mv.id
            JOIN test_case_version v ON v.id = mc.test_case_version_id
            JOIN test_case c ON c.id = v.test_case_id
            """;

    @GetMapping("/{module}/versions/{versionNo}/cases")
    public List<Map<String, Object>> cases(@PathVariable String module, @PathVariable int versionNo) {
        return Rows.list(jdbc.sql(CASES_SELECT + " WHERE m.code = :m AND mv.version_no = :n ORDER BY c.scenario_key")
                .param("m", module).param("n", versionNo));
    }

    /** Every module's newest test cases in one list: exactly what a schedule or run without a module would draw from. */
    @GetMapping("/cases")
    public List<Map<String, Object>> allCases() {
        return Rows.list(jdbc.sql(CASES_SELECT + """
                 WHERE mv.version_no = (SELECT MAX(x.version_no) FROM module_version x WHERE x.module_id = m.id)
                 ORDER BY m.code, c.scenario_key
                """));
    }

    /**
     * How many test cases and events (a case with several plan codes is several events) each tag selects in a module
     * version, so a schedule can show what it would run. Every active tag is listed, with zeros where nothing is tagged.
     */
    @GetMapping("/{module}/tag-counts")
    public Map<String, Object> tagCounts(@PathVariable String module, @org.springframework.web.bind.annotation.RequestParam(required = false) Integer versionNo) {
        Map<String, Object> version = Rows.list(jdbc.sql("""
                SELECT v.id, v.version_no, v.version, v.status FROM module_version v JOIN test_module m ON m.id = v.module_id
                WHERE m.code = :m AND (:n IS NULL OR v.version_no = :n) ORDER BY v.version_no DESC LIMIT 1
                """).param("m", module).param("n", versionNo)).stream().findFirst().orElse(null);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("version_no", version == null ? null : version.get("version_no"));
        out.put("version", version == null ? null : version.get("version"));
        out.put("status", version == null ? null : version.get("status"));
        out.putAll(countsFor(version == null ? List.of() : List.of(((Number) version.get("id")).longValue())));
        return out;
    }

    /** The same counts across every module's newest version: what a schedule or run without a module would select. */
    @GetMapping("/tag-counts")
    public Map<String, Object> allTagCounts() {
        return countsFor(jdbc.sql("SELECT MAX(id) FROM module_version GROUP BY module_id").query(Long.class).list());
    }

    private Map<String, Object> countsFor(List<Long> versionIds) {
        Map<String, long[]> counts = new java.util.HashMap<>();
        long totalCases = 0;
        long totalEvents = 0;
        long noMapperCases = 0;
        long noMapperEvents = 0;
        if (!versionIds.isEmpty()) {
            String perCase = """
                    (SELECT v2.id AS vid, CASE WHEN COUNT(p.plan_code) = 0 THEN 1 ELSE COUNT(p.plan_code) END AS n
                     FROM test_case_version v2 LEFT JOIN test_case_version_plan p ON p.test_case_version_id = v2.id GROUP BY v2.id)
                    """;
            jdbc.sql("""
                    SELECT t.category, t.code, COUNT(*), SUM(ev.n) FROM module_version_case mc
                    JOIN test_case_version v ON v.id = mc.test_case_version_id AND v.enabled = TRUE
                    JOIN test_case_version_tag vt ON vt.test_case_version_id = v.id JOIN tag t ON t.id = vt.tag_id
                    JOIN %s ev ON ev.vid = v.id WHERE mc.module_version_id IN (:mv) GROUP BY t.category, t.code
                    """.formatted(perCase)).param("mv", versionIds)
                    .query((rs, n) -> new Object[]{rs.getString(1) + ":" + rs.getString(2), rs.getLong(3), rs.getLong(4)}).list()
                    .forEach(r -> counts.put((String) r[0], new long[]{(Long) r[1], (Long) r[2]}));
            long[] total = jdbc.sql("""
                    SELECT COUNT(*), COALESCE(SUM(ev.n), 0) FROM module_version_case mc
                    JOIN test_case_version v ON v.id = mc.test_case_version_id AND v.enabled = TRUE JOIN %s ev ON ev.vid = v.id
                    WHERE mc.module_version_id IN (:mv)
                    """.formatted(perCase)).param("mv", versionIds).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
            totalCases = total[0];
            totalEvents = total[1];
            // test cases nobody put in a mapper group: they only run when no mapper is selected
            long[] n = jdbc.sql("""
                    SELECT COUNT(*), COALESCE(SUM(ev.n), 0) FROM module_version_case mc
                    JOIN test_case_version v ON v.id = mc.test_case_version_id AND v.enabled = TRUE JOIN %s ev ON ev.vid = v.id
                    WHERE mc.module_version_id IN (:mv) AND NOT EXISTS (
                      SELECT 1 FROM test_case_version_tag vt JOIN tag t ON t.id = vt.tag_id
                      WHERE vt.test_case_version_id = v.id AND t.category = 'MAPPER')
                    """.formatted(perCase)).param("mv", versionIds).query((rs, i2) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
            noMapperCases = n[0];
            noMapperEvents = n[1];
        }
        List<Map<String, Object>> tags = new java.util.ArrayList<>();
        for (Map<String, Object> t : Rows.list(jdbc.sql("SELECT category, code, label, description FROM tag WHERE active = TRUE ORDER BY category, label"))) {
            long[] c = counts.getOrDefault(t.get("category") + ":" + t.get("code"), new long[]{0, 0});
            Map<String, Object> row = new java.util.LinkedHashMap<>(t);
            row.put("cases", c[0]);
            row.put("events", c[1]);
            tags.add(row);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total_cases", totalCases);
        out.put("total_events", totalEvents);
        out.put("no_mapper_cases", noMapperCases);
        out.put("no_mapper_events", noMapperEvents);
        out.put("tags", tags);
        return out;
    }

    /** Latest revision of one scenario with everything the editor needs. */
    @GetMapping("/{module}/cases/{scenarioKey}")
    public Map<String, Object> caseDetail(@PathVariable String module, @PathVariable String scenarioKey) {
        return Rows.list(jdbc.sql(CASE_SQL + """
                 AND v.revision = (SELECT MAX(x.revision) FROM test_case_version x WHERE x.test_case_id = c.id)
                """).param("m", module).param("k", scenarioKey)).stream().findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Unknown scenario " + scenarioKey));
    }

    @GetMapping("/{module}/cases/{scenarioKey}/revisions")
    public List<Map<String, Object>> revisions(@PathVariable String module, @PathVariable String scenarioKey) {
        return Rows.list(jdbc.sql(CASE_SQL + " ORDER BY v.revision DESC").param("m", module).param("k", scenarioKey));
    }

    /** Which scenarios were added, removed or changed between two module versions. */
    @GetMapping("/{module}/versions/{from}/diff/{to}")
    public List<Map<String, Object>> diff(@PathVariable String module, @PathVariable int from, @PathVariable int to) {
        Map<String, String[]> a = hashes(module, from);
        Map<String, String[]> b = hashes(module, to);
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String k : keys) {
            String change = !a.containsKey(k) ? "ADDED" : !b.containsKey(k) ? "REMOVED"
                    : a.get(k)[0].equals(b.get(k)[0]) ? null : "CHANGED";
            if (change != null) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("scenario_key", k);
                row.put("change", change);
                row.put("from_revision", a.containsKey(k) ? a.get(k)[1] : null);
                row.put("to_revision", b.containsKey(k) ? b.get(k)[1] : null);
                out.add(row);
            }
        }
        return out;
    }

    private Map<String, String[]> hashes(String module, int versionNo) {
        Map<String, String[]> out = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT c.scenario_key, v.content_hash, v.revision FROM module_version mv
                JOIN test_module m ON m.id = mv.module_id JOIN module_version_case mc ON mc.module_version_id = mv.id
                JOIN test_case_version v ON v.id = mc.test_case_version_id JOIN test_case c ON c.id = v.test_case_id
                WHERE m.code = :m AND mv.version_no = :n
                """).param("m", module).param("n", versionNo)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3))})
                .list().forEach(r -> out.put(r[0], new String[]{r[1], r[2]}));
        return out;
    }

    private static final String CASE_SQL = """
            SELECT c.scenario_key, v.revision, v.name, v.description, v.kafka_kind, v.kafka_topic_pattern, v.event_type,
                   v.carrier, (SELECT LISTAGG(pc.plan_code, ',') WITHIN GROUP (ORDER BY pc.position)
                    FROM test_case_version_plan pc WHERE pc.test_case_version_id = v.id) AS plan_codes, v.payload_artifact_id, v.golden_artifact_id, v.expected_json,
                   v.timeout_seconds, v.enabled, v.change_note, v.created_by, v.created_at, v.payload_correlation_id,
                   (SELECT LISTAGG(LOWER(t.category) || ':' || t.code, ',') WITHIN GROUP (ORDER BY t.category, t.code)
                    FROM test_case_version_tag vt JOIN tag t ON t.id = vt.tag_id
                    WHERE vt.test_case_version_id = v.id) AS tags
            FROM test_case_version v JOIN test_case c ON c.id = v.test_case_id
            JOIN test_module m ON m.id = c.module_id WHERE m.code = :m AND c.scenario_key = :k
            """;

    /** Create or revise a scenario. Unchanged content returns changed=false and does not move the version. */
    @PutMapping("/{module}/cases/{scenarioKey}")
    public SaveResult save(@PathVariable String module, @PathVariable String scenarioKey,
                           @RequestBody TestCaseRequest request,
                           java.security.Principal principal) {
        return service.save(module, scenarioKey, request, principal.getName());
    }

    @PostMapping("/versions/{id}/status")
    public Map<String, Object> status(@PathVariable long id, @RequestBody Map<String, String> body,
                                      java.security.Principal principal) {
        service.setStatus(id, body.get("status"), principal.getName());
        return Map.of("id", id, "status", body.get("status"));
    }
}
