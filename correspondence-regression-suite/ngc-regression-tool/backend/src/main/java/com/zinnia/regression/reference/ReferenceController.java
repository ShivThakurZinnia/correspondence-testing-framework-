package com.zinnia.regression.reference;

import com.zinnia.regression.common.Rows;
import com.zinnia.regression.config.WiredEnvironment;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only pick-lists for test-case forms. CRUD on tags comes in a later phase. */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final JdbcClient jdbc;
    private final WiredEnvironment wired;

    public ReferenceController(JdbcClient jdbc, WiredEnvironment wired) {
        this.jdbc = jdbc;
        this.wired = wired;
    }

    @GetMapping("/tags")
    public List<Map<String, Object>> tags(@RequestParam(required = false) String category) {
        return Rows.list(jdbc.sql("""
                SELECT category, code, label, description FROM tag
                WHERE active = TRUE AND (:c IS NULL OR category = UPPER(:c)) ORDER BY category, code
                """).param("c", category));
    }

    @GetMapping("/environments")
    public List<Map<String, Object>> environments() {
        return Rows.list(jdbc.sql("SELECT code, name FROM environment WHERE active = TRUE AND (:w IS NULL OR code = :w) ORDER BY code")
                .param("w", wired.code().orElse(null)));
    }

    @GetMapping("/topics")
    public List<Map<String, Object>> topics(@RequestParam(required = false) String env) {
        return Rows.list(jdbc.sql("""
                SELECT e.code AS env, t.kind, t.topic_name FROM kafka_topic t
                JOIN environment e ON e.id = t.environment_id
                WHERE t.active = TRUE AND (:e IS NULL OR e.code = :e) AND (:w IS NULL OR e.code = :w)
                ORDER BY e.code, t.kind
                """).param("e", env).param("w", wired.code().orElse(null)));
    }
}
