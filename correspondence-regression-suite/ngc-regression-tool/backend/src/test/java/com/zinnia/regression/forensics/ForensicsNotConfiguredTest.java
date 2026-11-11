package com.zinnia.regression.forensics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:forensics-off;DB_CLOSE_DELAY=-1", "ngc.environment=dev", "regression.health.warm-up-on-start=false"})
@AutoConfigureMockMvc
@WithMockUser(username = "tester")
class ForensicsNotConfiguredTest {

    @Autowired MockMvc mvc;

    @Test
    void everyEndpointExplainsThatMongoIsNotConfigured() throws Exception {
        for (String url : new String[]{"/api/forensics/tree", "/api/forensics/events", "/api/forensics/events/abc"}) {
            mvc.perform(get(url)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("Mongo is not configured")));
        }
    }
}
