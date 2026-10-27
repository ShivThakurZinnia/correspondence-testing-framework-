package com.zinnia.regression.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zinnia.regression.artifact.ArtifactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** Boots the app on in-memory H2 so Flyway V1-V3 run for real. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:regression-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser(username = "tester")
class PayloadControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ArtifactRepository artifacts;
    @Autowired JdbcClient jdbc;

    @Test
    void migrationsApplyAndSeedReferenceData() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tag").query(Integer.class).single()).as("12 original + 9 new NGC mappers").isEqualTo(21);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM kafka_topic").query(Integer.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'TEST_RUN_CASE'"
                + " AND column_name = 'INJECTED_PAYLOAD_ARTIFACT_ID'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void uploadStoresPayloadUnmodifiedAndReturnsDetectedCorrelationId() throws Exception {
        String body = PayloadTest.PAYLOAD;

        mvc.perform(post("/api/payloads").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadCorrelationId").value(PayloadTest.ORIGINAL))
                .andExpect(jsonPath("$.carrier").value("GILI"))
                .andExpect(jsonPath("$.planCode").value("GCFIA903"));

        long id = jdbc.sql("SELECT MAX(id) FROM artifact").query(Long.class).single();
        assertThat(artifacts.findContent(id)).isEqualTo(body);
    }

    @Test
    void invalidPayloadIsRejectedAndNotStored() throws Exception {
        int before = jdbc.sql("SELECT COUNT(*) FROM artifact").query(Integer.class).single();

        mvc.perform(post("/api/payloads").contentType("application/json").content("{\"type\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM artifact").query(Integer.class).single()).isEqualTo(before);
    }

    @Test
    void goldenXmlIsStoredWhenWellFormedAndRejectedOtherwise() throws Exception {
        mvc.perform(post("/api/payloads/golden").contentType("application/xml").content("<L><P/></L>"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.artifactId").isNumber());
        mvc.perform(post("/api/payloads/golden").contentType("application/xml").content("<L><P>"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid").value(false));
        mvc.perform(post("/api/payloads/golden").contentType("application/xml")
                        .content("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM artifact WHERE kind = 'GOLDEN_XML'").query(Integer.class).single())
                .isGreaterThanOrEqualTo(1);
    }
}
