package com.zinnia.regression.common;

import org.springframework.jdbc.support.KeyHolder;

public final class Keys {

    private Keys() {
    }

    /** Generated id from an INSERT, regardless of how the driver cases the column name. */
    public static long idOf(KeyHolder keys) {
        return keys.getKeys().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("id"))
                .map(e -> ((Number) e.getValue()).longValue())
                .findFirst()
                .orElseThrow();
    }
}
