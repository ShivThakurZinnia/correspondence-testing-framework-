package com.zinnia.regression.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:security-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SecurityTest {

    @Autowired MockMvc mvc;

    @Test
    void apiRequiresTheConfiguredLogin() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").with(httpBasic("tester", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").with(httpBasic("someone", "test-pass"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").with(httpBasic("tester", "test-pass"))).andExpect(status().isOk());
    }

    @Test
    void serviceRefusesToStartWithoutAPassword() {
        assertThatThrownBy(() -> new SecurityConfig().users("admin", " ", new BCryptPasswordEncoder()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REGRESSION_PASSWORD");
    }
}
