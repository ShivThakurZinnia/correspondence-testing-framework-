package com.zinnia.regression.artifact;

import com.zinnia.regression.common.Rows;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final JdbcClient jdbc;

    public ArtifactController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Metadata plus text content (payloads, golden and generated XML are stored in the database). */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return Rows.list(jdbc.sql("""
                SELECT id, kind, name, content_type, size_bytes, sha256, created_by, created_at, content
                FROM artifact WHERE id = :i AND storage = 'DB'""").param("i", id)).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown artifact " + id));
    }
}
