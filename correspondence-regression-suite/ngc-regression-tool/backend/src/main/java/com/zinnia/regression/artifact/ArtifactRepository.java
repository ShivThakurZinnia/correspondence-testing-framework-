package com.zinnia.regression.artifact;

import com.zinnia.regression.common.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ArtifactRepository {

    private final JdbcClient jdbc;

    public ArtifactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a small text artifact (uploaded payload, golden XML) in the DB and returns its id. */
    public long saveInDb(String kind, String name, String contentType, String content, String createdBy) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO artifact (kind, name, content_type, sha256, size_bytes, storage, content, created_by)
                VALUES (:kind, :name, :contentType, :sha, :size, 'DB', :content, :createdBy)
                """)
                .param("kind", kind)
                .param("name", name)
                .param("contentType", contentType)
                .param("sha", sha256(bytes))
                .param("size", bytes.length)
                .param("content", content)
                .param("createdBy", createdBy)
                .update(keys);
        return Keys.idOf(keys);
    }

    public String findContent(long id) {
        return jdbc.sql("SELECT content FROM artifact WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    public String findSha256(long id) {
        return jdbc.sql("SELECT sha256 FROM artifact WHERE id = :id").param("id", id).query(String.class).single();
    }

    public java.util.Optional<String> findKind(long id) {
        return jdbc.sql("SELECT kind FROM artifact WHERE id = :id").param("id", id).query(String.class).optional();
    }

    public static String sha256Of(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
