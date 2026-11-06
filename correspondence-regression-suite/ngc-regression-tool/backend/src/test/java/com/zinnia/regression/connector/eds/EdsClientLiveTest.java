package com.zinnia.regression.connector.eds;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Downloads one real document. Runs only when API_GATEWAY_BASE_URL and EDS_LIVE_DOCUMENT_ID are set; prints no content. */
@EnabledIfEnvironmentVariable(named = "EDS_LIVE_DOCUMENT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "API_GATEWAY_BASE_URL", matches = ".+")
class EdsClientLiveTest {

    @Test
    void fetchesARealDocument() {
        EdsClient client = new EdsClient(System.getenv("API_GATEWAY_BASE_URL"),
                System.getenv().getOrDefault("CORRO_APPLICATION_HEADER", "nextgen-correspondence-service"),
                System.getenv().getOrDefault("CORRO_GATEWAY_COOKIE", ""), 10000, 60000, 52428800, new ObjectMapper());

        EdsDocument document = client.fetch(System.getenv("EDS_LIVE_DOCUMENT_ID"));

        System.out.println("LIVE EDS contentType=" + document.contentType() + " filename=" + document.filename()
                + " bytes=" + document.content().length);
        assertThat(document.content()).isNotEmpty();
        assertThat(new String(document.content(), 0, Math.min(5, document.content().length), StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF-");
    }
}
