package com.zinnia.regression.config;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one NGC environment this instance's Mongo, Kafka and S3 settings point at (NGC_ENVIRONMENT, e.g. dev).
 * Runs and schedules for any other environment are refused, so a QA run can never read or inject into Dev by mistake.
 * When unset (tests), every environment is allowed.
 */
@Component
public class WiredEnvironment {

    private final String code;

    public WiredEnvironment(@Value("${ngc.environment:}") String code) {
        this.code = code == null || code.isBlank() ? null : code.trim();
    }

    public Optional<String> code() {
        return Optional.ofNullable(code);
    }

    public boolean allows(String env) {
        return code == null || code.equals(env);
    }
}
