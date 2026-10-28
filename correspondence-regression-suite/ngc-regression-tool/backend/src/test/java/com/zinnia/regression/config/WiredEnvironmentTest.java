package com.zinnia.regression.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zinnia.regression.run.RunRequest;
import com.zinnia.regression.run.RunService;
import com.zinnia.regression.schedule.ScheduleRequest;
import com.zinnia.regression.schedule.ScheduleService;
import com.zinnia.regression.testcase.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:wired-test;DB_CLOSE_DELAY=-1", "ngc.environment=dev"})
@AutoConfigureMockMvc
@WithMockUser(username = "tester")
class WiredEnvironmentTest {

    @Autowired MockMvc mvc;
    @Autowired RunService runs;
    @Autowired ScheduleService schedules;

    @Test
    void onlyTheWiredEnvironmentIsOfferedAndSeededWithItsTopics() throws Exception {
        mvc.perform(get("/api/reference/environments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("dev"));
        mvc.perform(get("/api/reference/topics")).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.kind=='SOR')].topic_name").value("zinnia.correspondence.sor"))
                .andExpect(jsonPath("$[?(@.kind=='BPM')].topic_name").value("zinnia.correspondence.dev"));
        mvc.perform(get("/api/dashboard")).andExpect(jsonPath("$.tiles.length()").value(0));
    }

    @Test
    void runsAndSchedulesForAnotherEnvironmentAreRefused() {
        assertThatThrownBy(() -> runs.create(new RunRequest("policy-page", null, "qa", null, "ui"), "t"))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.errors()).contains("This instance is connected to dev, not qa"));
        assertThatThrownBy(() -> schedules.create(new ScheduleRequest("s", "policy-page", null, "qa", null, "0 0 6 * * *", "UTC", null, null), "t"))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.errors()).contains("This instance is connected to dev, not qa"));
    }

    @Test
    void withoutAConfiguredEnvironmentNothingIsRestricted() {
        assertThat(new WiredEnvironment("").allows("qa")).isTrue();
        assertThat(new WiredEnvironment(" dev ").allows("dev")).isTrue();
        assertThat(new WiredEnvironment("dev").allows("qa")).isFalse();
    }
}
