package com.zinnia.regression.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Reads KEY=VALUE lines from .env in the working directory; real env vars and system properties still win. */
public final class DotenvLoader {

    private DotenvLoader() {
    }

    public static Map<String, Object> load() {
        return load(Path.of(".env"));
    }

    static Map<String, Object> load(Path file) {
        Map<String, Object> props = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq < 1) {
                    continue;
                }
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                props.put(line.substring(0, eq).strip(), value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
        return props;
    }
}
