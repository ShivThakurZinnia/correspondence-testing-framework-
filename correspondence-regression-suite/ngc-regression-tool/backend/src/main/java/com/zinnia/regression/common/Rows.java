package com.zinnia.regression.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class Rows {

    private Rows() {
    }

    /** Rows with lowercase column names so API JSON is identical on H2 (uppercase) and PostgreSQL (lowercase). */
    public static List<Map<String, Object>> list(JdbcClient.StatementSpec sql) {
        return sql.query().listOfRows().stream().map(Rows::lower).toList();
    }

    public static Map<String, Object> lower(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k.toLowerCase(), v));
        return out;
    }
}
