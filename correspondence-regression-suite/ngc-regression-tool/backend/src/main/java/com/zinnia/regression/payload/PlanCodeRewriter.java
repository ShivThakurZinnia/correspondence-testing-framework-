package com.zinnia.regression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Sets the PLAN_CODE identifier of an event so one uploaded payload can be played for several plan codes. */
@Component
public class PlanCodeRewriter {

    private final ObjectMapper mapper;

    public PlanCodeRewriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Real NGC events repeat the plan code inside {@code data} (a JSON string or object); keep it in step. */
    private void setPlanCodeInData(ObjectNode root, String planCode) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode data = root.get("data");
        if (data instanceof ObjectNode o && o.has("planCode")) {
            o.put("planCode", planCode);
        } else if (data != null && data.isTextual() && data.asText().trim().startsWith("{")) {
            try {
                JsonNode inner = mapper.readTree(data.asText());
                if (inner instanceof ObjectNode o && o.has("planCode")) {
                    o.put("planCode", planCode);
                    root.put("data", mapper.writeValueAsString(o));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // data is not JSON (e.g. XML): leave it alone
            }
        }
    }

    /** Replaces the value of the PLAN_CODE (or planCode) identifier; adds the identifier when the payload has none. */
    public String apply(String payloadJson, String planCode) {
        try {
            JsonNode root = mapper.readTree(payloadJson);
            if (!(root instanceof ObjectNode obj) || !(obj.get("identifiers") instanceof ArrayNode ids)) {
                throw new IllegalStateException("Payload has no identifiers array");
            }
            boolean replaced = false;
            for (JsonNode item : ids) {
                String name = item.path("identifier").asText("");
                if (item instanceof ObjectNode o && (name.equalsIgnoreCase("PLAN_CODE") || name.equalsIgnoreCase("planCode"))) {
                    o.put("value", planCode);
                    replaced = true;
                }
            }
            if (!replaced) {
                ids.addObject().put("identifier", "PLAN_CODE").put("value", planCode);
            }
            setPlanCodeInData(obj, planCode);
            return mapper.writeValueAsString(obj);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Payload is not valid JSON", e);
        }
    }
}
