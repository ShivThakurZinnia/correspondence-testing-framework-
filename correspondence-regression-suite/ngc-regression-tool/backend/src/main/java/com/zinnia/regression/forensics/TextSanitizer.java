package com.zinnia.regression.forensics;

import java.util.regex.Pattern;

/** Makes NGC error text safe to show: drops stack frames, masks personal data and truncates. Policy numbers are kept. */
final class TextSanitizer {

    static final int MAX_LENGTH = 400;

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b\\d{13,19}\\b");
    private static final Pattern SENSITIVE_PAIR = Pattern.compile(
            "(?i)(?<!\\w)(\"?)(first[_ ]?name|last[_ ]?name|middle[_ ]?name|full[_ ]?name|name|dob|date[_ ]?of[_ ]?birth|birth[_ ]?date"
                    + "|ssn|tax[_ ]?id|tin|address\\w*|street\\w*|city|zip\\w*|phone\\w*|email\\w*|account[_ ]?number)\\1"
                    + "\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|[^,}\\]\\s]+)");

    private TextSanitizer() {
    }

    static String clean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("at ") || trimmed.startsWith("... ")) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append(" | ");
            }
            kept.append(trimmed);
        }
        String masked = SENSITIVE_PAIR.matcher(kept).replaceAll("$2=[redacted]");
        masked = EMAIL.matcher(masked).replaceAll("[email]");
        masked = SSN.matcher(masked).replaceAll("[ssn]");
        masked = CARD.matcher(masked).replaceAll("[number]");
        return masked.length() > MAX_LENGTH ? masked.substring(0, MAX_LENGTH) + "…" : masked;
    }
}
