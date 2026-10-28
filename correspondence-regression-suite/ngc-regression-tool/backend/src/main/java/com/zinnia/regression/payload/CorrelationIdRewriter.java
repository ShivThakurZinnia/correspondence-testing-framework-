package com.zinnia.regression.payload;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Gives every run its own correlationId: replaces each occurrence of the id found in the uploaded payload
 * (top-level field and any identifier/data field that echoes it) with a new UUID.
 */
@Component
public class CorrelationIdRewriter {

    public String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    public String rewrite(String payloadJson, String originalId, String newId) {
        if (originalId.equalsIgnoreCase(newId)) {
            throw new IllegalStateException("New correlationId must differ from the payload's original id");
        }
        Pattern original = Pattern.compile(Pattern.quote(originalId), Pattern.CASE_INSENSITIVE);
        String rewritten = original.matcher(payloadJson).replaceAll(Matcher.quoteReplacement(newId));
        if (original.matcher(rewritten).find()) {
            throw new IllegalStateException("Original correlationId still present after rewrite");
        }
        return rewritten;
    }
}
