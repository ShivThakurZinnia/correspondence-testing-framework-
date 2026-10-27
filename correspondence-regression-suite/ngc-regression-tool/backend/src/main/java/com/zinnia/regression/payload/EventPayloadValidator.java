package com.zinnia.regression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Checks an uploaded Kafka event against the fields NGC's EventModel requires. */
@Component
public class EventPayloadValidator {

    private final ObjectMapper mapper;

    public EventPayloadValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public PayloadInfo validate(String json) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            return new PayloadInfo(null, null, null, null, null, List.of("Payload is not valid JSON"));
        }
        if (root == null || !root.isObject()) {
            return new PayloadInfo(null, null, null, null, null, List.of("Payload must be a JSON object"));
        }

        String correlationId = text(root, "correlationid");
        if (correlationId == null) {
            errors.add("correlationid is required");
        } else if (!isUuid(correlationId)) {
            errors.add("correlationid must be a UUID");
        }
        String carrier = text(root, "carrier");
        String type = text(root, "type");
        if (text(root, "source") == null) {
            errors.add("source is required");
        }
        if (carrier == null) {
            errors.add("carrier is required");
        }
        if (type == null) {
            errors.add("type is required");
        }
        JsonNode identifiers = root.get("identifiers");
        if (identifiers == null || !identifiers.isArray() || identifiers.isEmpty()) {
            errors.add("identifiers must be a non-empty array");
        }
        return new PayloadInfo(correlationId, carrier, type, identifier(identifiers, "PLAN_CODE", "planCode"),
                identifier(identifiers, "POLICY_NUMBER", "policyNumber"), errors);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    /** NGC events spell identifiers both as PLAN_CODE and planCode; the first non-null match wins. */
    private static String identifier(JsonNode identifiers, String... names) {
        if (identifiers == null || !identifiers.isArray()) {
            return null;
        }
        for (JsonNode item : identifiers) {
            JsonNode key = item.get("identifier");
            if (key == null || !item.hasNonNull("value")) {
                continue;
            }
            for (String name : names) {
                if (name.equalsIgnoreCase(key.asText())) {
                    return item.get("value").asText();
                }
            }
        }
        return null;
    }

    static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
