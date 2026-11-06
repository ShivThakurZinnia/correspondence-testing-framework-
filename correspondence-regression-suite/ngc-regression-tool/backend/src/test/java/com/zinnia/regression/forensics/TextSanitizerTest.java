package com.zinnia.regression.forensics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSanitizerTest {

    @Test
    void emailsSsnAndCardNumbersAreMasked() {
        String out = TextSanitizer.clean("Could not email jane.doe+x@example.com for 123-45-6789 card 4111111111111111");

        assertThat(out).isEqualTo("Could not email [email] for [ssn] card [number]");
    }

    @Test
    void personalKeyValuePairsAreRedactedInJsonAndKeyEqualsStyle() {
        String out = TextSanitizer.clean("Bad party {\"firstName\":\"Jane\",\"dob\":\"1980-01-02\",\"planCode\":\"GCFIA903\"} email=a@b.co street=\"1 Main St\"");

        assertThat(out).contains("firstName=[redacted]", "dob=[redacted]", "planCode\":\"GCFIA903");
        assertThat(out).doesNotContain("Jane", "1980-01-02", "1 Main St", "a@b.co");
    }

    @Test
    void policyNumbersAndWorkerNamesAreKept() {
        String out = TextSanitizer.clean("PolicyDataWithLatestVersionWorker failed for policy AIUL300626252 workerName=SearchDocumentWorker");

        assertThat(out).contains("AIUL300626252", "workerName=SearchDocumentWorker");
    }

    @Test
    void stackFramesAreDroppedAndLinesJoined() {
        String out = TextSanitizer.clean("java.lang.IllegalStateException: boom\n\tat com.zinnia.Foo.bar(Foo.java:10)\n\tat x.Y.z(Y.java:2)\nCaused by: java.io.IOException: disk\n\t... 12 more");

        assertThat(out).isEqualTo("java.lang.IllegalStateException: boom | Caused by: java.io.IOException: disk");
    }

    @Test
    void longTextIsTruncatedAndBlankBecomesNull() {
        assertThat(TextSanitizer.clean("x".repeat(1000))).hasSize(TextSanitizer.MAX_LENGTH + 1).endsWith("…");
        assertThat(TextSanitizer.clean("  ")).isNull();
        assertThat(TextSanitizer.clean(null)).isNull();
    }
}
