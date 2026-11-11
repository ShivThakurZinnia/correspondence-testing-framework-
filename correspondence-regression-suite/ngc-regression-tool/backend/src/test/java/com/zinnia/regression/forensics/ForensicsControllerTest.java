package com.zinnia.regression.forensics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zinnia.regression.forensics.ForensicsModel.Criteria;
import com.zinnia.regression.forensics.ForensicsModel.EventSummary;
import com.zinnia.regression.forensics.ForensicsModel.History;
import com.zinnia.regression.forensics.ForensicsModel.RejectedCount;
import com.zinnia.regression.forensics.ForensicsModel.SearchResult;
import com.zinnia.regression.forensics.ForensicsModel.Stage;
import com.zinnia.regression.forensics.ForensicsModel.Target;
import com.zinnia.regression.health.HealthSource;
import com.zinnia.regression.health.HealthSource.EventStatusCount;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:forensics-test;DB_CLOSE_DELAY=-1", "ngc.environment=dev", "regression.health.warm-up-on-start=false"})
@AutoConfigureMockMvc
@WithMockUser(username = "tester")
class ForensicsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ForensicsSource source;
    @MockitoBean HealthSource health;

    static EventSummary summary() {
        return new EventSummary("u1", "c1", "GILI", "policy.activation.completed", "AIUL300626252", "GCFIA903", "zahara", "FAILED",
                "TECHNICAL_FAILURE", "Zahara 404", false, Instant.parse("2026-10-28T10:00:00Z"), Instant.parse("2026-10-28T10:01:00Z"),
                List.of(new Target("OWNER", "TECHNICAL_FAILURE", "PRINT", null)));
    }

    @Test
    void treeCombinesStatusCountsAndRejectedCounts() throws Exception {
        when(health.statusCounts(any())).thenReturn(List.of(new EventStatusCount("GILI", "policy.activation.completed", "SENT_TO_CDS", null, 9),
                new EventStatusCount("GILI", "policy.activation.completed", "FAILED", "TECHNICAL_FAILURE", 1)));
        when(source.rejectedCounts(any())).thenReturn(List.of(new RejectedCount("GILI", "DEAD_LETTER", 2)));

        mvc.perform(get("/api/forensics/tree?hours=48")).andExpect(status().isOk())
                .andExpect(jsonPath("$.window_hours").value(48))
                .andExpect(jsonPath("$.carriers[0].carrier").value("GILI"))
                .andExpect(jsonPath("$.carriers[0].total").value(10))
                .andExpect(jsonPath("$.carriers[0].rejected_dead_letters").value(2))
                .andExpect(jsonPath("$.carriers[0].event_types[0].technical").value(1))
                .andExpect(jsonPath("$.carriers[0].event_types[0].ok").value(9));
    }

    @Test
    void searchPassesTrimmedFiltersAndClampsWindowAndLimit() throws Exception {
        when(source.search(any())).thenReturn(new SearchResult(List.of(summary()), List.of(), false, null));

        mvc.perform(get("/api/forensics/events?carrier= GILI &eventType=policy.activation.completed&outcome=TECHNICAL&hours=99999&limit=1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].policy_number").value("AIUL300626252"))
                .andExpect(jsonPath("$.events[0].targets[0].delivery_type").value("PRINT"))
                .andExpect(jsonPath("$.has_more").value(false));

        ArgumentCaptor<Criteria> c = ArgumentCaptor.forClass(Criteria.class);
        verify(source).search(c.capture());
        assertThat(c.getValue().carrier()).isEqualTo("GILI");
        assertThat(c.getValue().outcome()).isEqualTo("technical");
        assertThat(c.getValue().limit()).isEqualTo(100);
        assertThat(Duration.between(c.getValue().from(), Instant.now()).toHours()).isBetween(2159L, 2160L);
        assertThat(c.getValue().correlationId()).isNull();
    }

    @Test
    void badInputIsRejectedWithReadableErrors() throws Exception {
        mvc.perform(get("/api/forensics/events?outcome=weird&before=yesterday&policyNumber=" + "x".repeat(200)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void historyReturnsTheTimelineOr404() throws Exception {
        when(source.history("c1", null)).thenReturn(new History("c1", null, 1, List.of(summary()),
                List.of(new Stage("RECEIVED", "Received", "done", Instant.parse("2026-10-28T10:00:00Z"))), List.of(), List.of(),
                List.of("Replayed: 2 processing attempts share this correlationId.")));
        when(source.history("none", null)).thenReturn(null);
        when(source.history("c1", "u2")).thenReturn(new History("c1", "u2", 2, List.of(summary()), List.of(), List.of(), List.of(), List.of()));

        mvc.perform(get("/api/forensics/events/c1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.correlation_id").value("c1"))
                .andExpect(jsonPath("$.stages[0].state").value("done"))
                .andExpect(jsonPath("$.markers[0]").value("Replayed: 2 processing attempts share this correlationId."));
        mvc.perform(get("/api/forensics/events/c1?uniqueId=u2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_attempt").value("u2")).andExpect(jsonPath("$.attempt_count").value(2));
        mvc.perform(get("/api/forensics/events/none")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0]").value("NGC holds nothing for this correlationId"));
    }

    @Test
    void aSlowDatabaseGetsTheIndexHint() throws Exception {
        when(source.search(any())).thenThrow(new com.mongodb.MongoExecutionTimeoutException(50, "operation exceeded time limit"));

        mvc.perform(get("/api/forensics/events?carrier=GILI")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("took too long to answer")));
    }
}
