package com.zinnia.regression.connector.eds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Downloads real documents. Runs only when API_GATEWAY_BASE_URL is set; prints no document content. */
@EnabledIfEnvironmentVariable(named = "API_GATEWAY_BASE_URL", matches = ".+")
class EdsClientLiveTest {

    static final String QA_POLICY_PACKAGE_ID = "6aec55e917b2c84e9b6ff9e5";

    EdsClient client() {
        return new EdsClient(System.getenv("API_GATEWAY_BASE_URL"),
                System.getenv().getOrDefault("CORRO_APPLICATION_HEADER", "nextgen-correspondence-service"),
                System.getenv().getOrDefault("CORRO_GATEWAY_COOKIE", ""), 10000, 120000, 52428800, new ObjectMapper());
    }

    static String magic(byte[] content, int from, int length) {
        return new String(content, from, length, StandardCharsets.ISO_8859_1);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EDS_LIVE_DOCUMENT_ID", matches = ".+")
    void fetchesTheDocumentNamedByTheEnvironment() {
        EdsDocument document = client().fetch(System.getenv("EDS_LIVE_DOCUMENT_ID"));

        System.out.println("LIVE EDS contentType=" + document.contentType() + " filename=" + document.filename()
                + " bytes=" + document.content().length);
        assertThat(document.content()).isNotEmpty();
        assertThat(magic(document.content(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void downloadsTheQaPolicyPackagePdf() {
        assumeTrue(System.getenv("API_GATEWAY_BASE_URL").contains("correspondence-qa"), "the document exists in QA only");

        EdsDocument document = client().fetch(QA_POLICY_PACKAGE_ID);

        System.out.println("LIVE EDS filename=" + document.filename() + " bytes=" + document.content().length);
        assertThat(document.filename()).endsWithIgnoringCase(".pdf");
        assertThat(document.content()).hasSize(1104016);
        assertThat(magic(document.content(), 0, 5)).isEqualTo("%PDF-");
        assertThat(new String(document.content(), StandardCharsets.ISO_8859_1).stripTrailing()).endsWith("%%EOF");
    }
}
