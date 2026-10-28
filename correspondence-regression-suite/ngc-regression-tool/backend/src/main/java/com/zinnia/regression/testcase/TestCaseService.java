package com.zinnia.regression.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zinnia.regression.artifact.ArtifactRepository;
import com.zinnia.regression.common.Keys;
import com.zinnia.regression.payload.EventPayloadValidator;
import com.zinnia.regression.payload.PayloadInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves test cases as immutable revisions and rolls every change into a new immutable module version.
 * Saving identical content is a no-op, so versions only move when something really changed.
 */
@Service
public class TestCaseService {

    private final JdbcClient jdbc;
    private final ArtifactRepository artifacts;
    private final EventPayloadValidator payloadValidator;
    private final ObjectMapper canonical;

    public TestCaseService(JdbcClient jdbc, ArtifactRepository artifacts, EventPayloadValidator payloadValidator,
                           ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.payloadValidator = payloadValidator;
        this.canonical = mapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional
    public SaveResult save(String moduleCode, String scenarioKey, TestCaseRequest req, String user) {
        long moduleId = jdbc.sql("SELECT id FROM test_module WHERE code = :c").param("c", moduleCode)
                .query(Long.class).optional()
                .orElseThrow(() -> new ValidationException(List.of("Unknown module: " + moduleCode)));

        List<String> errors = new ArrayList<>();
        List<Long> tagIds = new ArrayList<>();
        String expected = validate(req, scenarioKey, errors, tagIds);
        List<String> planCodes = planCodes(req.planCodes(), errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        PayloadInfo payload = payloadValidator.validate(artifacts.findContent(req.payloadArtifactId()));
        boolean enabled = req.enabled() == null || req.enabled();
        int timeout = req.timeoutSeconds() == null ? 600 : req.timeoutSeconds();
        String hash = contentHash(req, expected, timeout, enabled, tagIds, planCodes);

        long testCaseId = jdbc.sql("SELECT id FROM test_case WHERE module_id = :m AND scenario_key = :k")
                .param("m", moduleId).param("k", scenarioKey).query(Long.class).optional()
                .orElseGet(() -> {
                    KeyHolder keys = new GeneratedKeyHolder();
                    jdbc.sql("INSERT INTO test_case (module_id, scenario_key) VALUES (:m, :k)")
                            .param("m", moduleId).param("k", scenarioKey).update(keys);
                    return Keys.idOf(keys);
                });

        Optional<Latest> latest = jdbc.sql("""
                SELECT id, revision, content_hash FROM test_case_version
                WHERE test_case_id = :t ORDER BY revision DESC LIMIT 1
                """).param("t", testCaseId)
                .query((rs, n) -> new Latest(rs.getLong(1), rs.getInt(2), rs.getString(3))).optional();

        boolean changed = latest.isEmpty() || !hash.equals(latest.get().hash());
        long versionId;
        int revision;
        if (changed) {
            revision = latest.map(r -> r.revision() + 1).orElse(1);
            versionId = insertRevision(testCaseId, revision, req, expected, timeout, enabled, hash, payload, user, planCodes);
            for (int i = 0; i < planCodes.size(); i++) {
                jdbc.sql("INSERT INTO test_case_version_plan (test_case_version_id, plan_code, position) VALUES (:v, :p, :i)")
                        .param("v", versionId).param("p", planCodes.get(i)).param("i", i).update();
            }
            for (Long tagId : tagIds) {
                jdbc.sql("INSERT INTO test_case_version_tag (test_case_version_id, tag_id) VALUES (:v, :t)")
                        .param("v", versionId).param("t", tagId).update();
            }
        } else {
            versionId = latest.get().id();
            revision = latest.get().revision();
        }

        long[] mv = changed || noModuleVersionYet(moduleId)
                ? rollModuleVersion(moduleId, req.changeNote(), user)
                : currentModuleVersion(moduleId);
        return new SaveResult(testCaseId, versionId, revision, changed, mv[0], versionLabel((int) mv[1]));
    }

    @Transactional
    public void setStatus(long moduleVersionId, String status, String user) {
        if (!List.of("DRAFT", "APPROVED", "RELEASE_GATE", "RETIRED").contains(status)) {
            throw new ValidationException(List.of("Unknown status: " + status));
        }
        boolean approving = status.equals("APPROVED") || status.equals("RELEASE_GATE");
        int updated = jdbc.sql("""
                UPDATE module_version SET status = :s,
                  approved_by = CASE WHEN :a THEN :u ELSE approved_by END,
                  approved_at = CASE WHEN :a THEN CURRENT_TIMESTAMP ELSE approved_at END
                WHERE id = :id
                """).param("s", status).param("a", approving).param("u", user).param("id", moduleVersionId).update();
        if (updated == 0) {
            throw new ValidationException(List.of("Unknown module version: " + moduleVersionId));
        }
    }

    private record Latest(long id, int revision, String hash) {
    }

    static String versionLabel(int versionNo) {
        return "1." + (versionNo - 1) + ".0";
    }

    private String validate(TestCaseRequest req, String scenarioKey, List<String> errors, List<Long> tagIds) {
        if (!scenarioKey.matches("[a-z0-9][a-z0-9-]{2,118}")) {
            errors.add("scenarioKey must be lowercase letters, digits and dashes (3-119 chars)");
        }
        blank(req.name(), "name", errors);
        blank(req.eventType(), "eventType", errors);
        blank(req.carrier(), "carrier", errors);
        if (req.timeoutSeconds() != null && req.timeoutSeconds() <= 0) {
            errors.add("timeoutSeconds must be positive");
        }

        if (req.kafkaKind() == null || !List.of("SOR", "BPM").contains(req.kafkaKind())) {
            errors.add("kafkaKind is required and must be SOR or BPM (the tester must choose the topic)");
        } else if (req.kafkaTopicPattern() == null || req.kafkaTopicPattern().isBlank()) {
            errors.add("kafkaTopicPattern is required");
        } else if (!topicKnown(req.kafkaKind(), req.kafkaTopicPattern())) {
            errors.add("kafkaTopicPattern '" + req.kafkaTopicPattern() + "' matches no configured " + req.kafkaKind()
                    + " topic (use {env} for the environment)");
        }

        if (req.payloadArtifactId() == null) {
            errors.add("payloadArtifactId is required (upload the event first)");
        } else if (!artifacts.findKind(req.payloadArtifactId()).filter("EVENT_PAYLOAD"::equals).isPresent()) {
            errors.add("payloadArtifactId must reference an uploaded EVENT_PAYLOAD");
        }
        if (req.goldenArtifactId() != null
                && !artifacts.findKind(req.goldenArtifactId()).filter("GOLDEN_XML"::equals).isPresent()) {
            errors.add("goldenArtifactId must reference a GOLDEN_XML artifact");
        }

        String expected = canonicalExpected(req.expectedJson(), errors);

        boolean hasClient = false;
        for (String ref : req.tags() == null ? List.<String>of() : req.tags()) {
            String[] parts = ref.split(":", 2);
            Optional<Long> id = parts.length == 2
                    ? jdbc.sql("SELECT id FROM tag WHERE category = :c AND code = :code AND active = TRUE")
                            .param("c", parts[0].toUpperCase()).param("code", parts[1]).query(Long.class).optional()
                    : Optional.empty();
            if (id.isEmpty()) {
                errors.add("Unknown or inactive tag: " + ref);
            } else if (!tagIds.contains(id.get())) {
                tagIds.add(id.get());
                hasClient |= parts[0].equalsIgnoreCase("client");
            }
        }
        if (!hasClient) {
            errors.add("At least one client:<code> tag is required");
        }
        return expected;
    }

    /** "No silent pass": expected must be a JSON object that asserts the final incoming_events status. */
    private String canonicalExpected(String json, List<String> errors) {
        if (json == null || json.isBlank()) {
            errors.add("expectedJson is required");
            return null;
        }
        try {
            JsonNode node = canonical.readTree(json);
            if (!node.isObject() || !node.path("incomingEvent").path("status").isTextual()) {
                errors.add("expectedJson must assert incomingEvent.status");
                return null;
            }
            rejectNegativeExpectations(node, errors);
            return canonical.writeValueAsString(canonical.treeToValue(node, Object.class));
        } catch (Exception e) {
            errors.add("expectedJson is not valid JSON");
            return null;
        }
    }

    private static final List<String> FAILURE_STATUSES = List.of("FAILED", "BUSINESS_FAILURE", "TECHNICAL_FAILURE",
            "BUSINESS_AND_TECHNICAL_FAILURE", "NOT_SENT_DUE_TO_OTHER_PARTY_FAILED");
    private static final List<String> FAILURE_STEPS = List.of("requestInvalid", "requestTerminated", "caseStatusFailed");

    /** Test cases describe successful processing only; failures are detected automatically, never expected. */
    private static void rejectNegativeExpectations(JsonNode node, List<String> errors) {
        String status = node.path("incomingEvent").path("status").asText();
        if (FAILURE_STATUSES.contains(status)) {
            errors.add("incomingEvent.status must be a success status, not " + status);
        }
        for (JsonNode t : node.path("targets")) {
            if (FAILURE_STATUSES.contains(t.path("status").asText())) {
                errors.add("targets[" + t.path("role").asText() + "].status must be a success status");
            }
        }
        for (JsonNode j : node.path("journey")) {
            if (FAILURE_STEPS.contains(j.asText())) {
                errors.add("journey step " + j.asText() + " is a failure and cannot be expected");
            }
        }
    }

    private boolean topicKnown(String kind, String pattern) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM kafka_topic t JOIN environment e ON e.id = t.environment_id
                WHERE t.kind = :k AND t.active = TRUE AND t.topic_name = REPLACE(:p, '{env}', e.code)
                """).param("k", kind).param("p", pattern).query(Integer.class).single() > 0;
    }

    private String contentHash(TestCaseRequest req, String expected, int timeout, boolean enabled, List<Long> tagIds,
                              List<String> planCodes) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.name()).append('|').append(nullToEmpty(req.description())).append('|')
                .append(req.kafkaKind()).append('|').append(req.kafkaTopicPattern()).append('|')
                .append(req.eventType()).append('|').append(req.carrier()).append('|')
                .append(new TreeSet<>(planCodes)).append('|')
                .append(artifacts.findSha256(req.payloadArtifactId())).append('|')
                .append(req.goldenArtifactId() == null ? "" : artifacts.findSha256(req.goldenArtifactId())).append('|')
                .append(expected).append('|').append(timeout).append('|').append(enabled).append('|')
                .append(new TreeSet<>(tagIds));
        return ArtifactRepository.sha256Of(sb.toString());
    }

    private long insertRevision(long testCaseId, int revision, TestCaseRequest req, String expected, int timeout,
                                boolean enabled, String hash, PayloadInfo payload, String user,
                                List<String> planCodes) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO test_case_version (test_case_id, revision, name, description, kafka_kind,
                  kafka_topic_pattern, event_type, carrier, plan_code, payload_artifact_id, golden_artifact_id,
                  expected_json, timeout_seconds, enabled, content_hash, change_note, created_by,
                  payload_correlation_id)
                VALUES (:t, :rev, :name, :descr, :kind, :topic, :event, :carrier, :plan, :payload, :golden,
                  :expected, :timeout, :enabled, :hash, :note, :user, :corr)
                """)
                .param("t", testCaseId).param("rev", revision).param("name", req.name())
                .param("descr", req.description()).param("kind", req.kafkaKind())
                .param("topic", req.kafkaTopicPattern()).param("event", req.eventType())
                .param("carrier", req.carrier()).param("plan", planCodes.isEmpty() ? null : planCodes.get(0))
                .param("payload", req.payloadArtifactId()).param("golden", req.goldenArtifactId())
                .param("expected", expected).param("timeout", timeout).param("enabled", enabled)
                .param("hash", hash).param("note", req.changeNote()).param("user", user)
                .param("corr", payload.correlationId())
                .update(keys);
        return Keys.idOf(keys);
    }

    private boolean noModuleVersionYet(long moduleId) {
        return jdbc.sql("SELECT COUNT(*) FROM module_version WHERE module_id = :m").param("m", moduleId)
                .query(Integer.class).single() == 0;
    }

    private long[] currentModuleVersion(long moduleId) {
        return jdbc.sql("SELECT id, version_no FROM module_version WHERE module_id = :m ORDER BY version_no DESC LIMIT 1")
                .param("m", moduleId)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
    }

    /** Snapshot the latest revision of every test case in the module; skip if identical to the last version. */
    private long[] rollModuleVersion(long moduleId, String changeNote, String user) {
        List<long[]> members = jdbc.sql("""
                SELECT v.id FROM test_case_version v JOIN test_case c ON c.id = v.test_case_id
                WHERE c.module_id = :m
                  AND v.revision = (SELECT MAX(x.revision) FROM test_case_version x WHERE x.test_case_id = c.id)
                ORDER BY v.id
                """).param("m", moduleId).query((rs, n) -> new long[]{rs.getLong(1)}).list();
        List<String> hashes = jdbc.sql("""
                SELECT v.content_hash FROM test_case_version v JOIN test_case c ON c.id = v.test_case_id
                WHERE c.module_id = :m
                  AND v.revision = (SELECT MAX(x.revision) FROM test_case_version x WHERE x.test_case_id = c.id)
                ORDER BY v.content_hash
                """).param("m", moduleId).query(String.class).list();
        String moduleHash = ArtifactRepository.sha256Of(String.join(",", hashes));

        Optional<long[]> same = jdbc.sql("""
                SELECT id, version_no FROM module_version WHERE module_id = :m AND content_hash = :h
                ORDER BY version_no DESC LIMIT 1
                """).param("m", moduleId).param("h", moduleHash)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).optional();
        Optional<long[]> latest = noModuleVersionYet(moduleId) ? Optional.empty() : Optional.of(currentModuleVersion(moduleId));
        if (same.isPresent() && latest.isPresent() && same.get()[0] == latest.get()[0]) {
            return same.get();
        }

        int versionNo = latest.map(l -> (int) l[1] + 1).orElse(1);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO module_version (module_id, version_no, version, content_hash, change_note, created_by)
                VALUES (:m, :no, :label, :hash, :note, :user)
                """).param("m", moduleId).param("no", versionNo).param("label", versionLabel(versionNo))
                .param("hash", moduleHash)
                .param("note", changeNote == null || changeNote.isBlank() ? "Updated test cases" : changeNote)
                .param("user", user).update(keys);
        long id = Keys.idOf(keys);
        for (long[] member : members) {
            jdbc.sql("INSERT INTO module_version_case (module_version_id, test_case_version_id) VALUES (:mv, :v)")
                    .param("mv", id).param("v", member[0]).update();
        }
        return new long[]{id, versionNo};
    }

    private static final java.util.regex.Pattern PLAN_CODE = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,30}");

    /** Trims, drops blanks and repeats (ignoring case) and keeps the tester's order. */
    static List<String> planCodes(List<String> raw, List<String> errors) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String code : raw == null ? List.<String>of() : raw) {
            String c = code == null ? "" : code.trim();
            if (c.isEmpty() || !seen.add(c.toUpperCase())) {
                continue;
            }
            if (!PLAN_CODE.matcher(c).matches()) {
                errors.add("Plan code '" + c + "' may only contain letters, digits, dots, dashes and underscores (max 30)");
            } else {
                out.add(c);
            }
        }
        if (out.size() > 50) {
            errors.add("At most 50 plan codes per test case");
        }
        return out;
    }

    private static void blank(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + " is required");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
