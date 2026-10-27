package com.zinnia.regression.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:health-test;DB_CLOSE_DELAY=-1", "ngc.environment=dev", "regression.health.warm-up-on-start=false"})
@AutoConfigureMockMvc
@WithMockUser(username = "tester")
class HealthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired HealthSnapshotRecorder recorder;
    @Autowired HealthService service;
    @MockitoBean HealthSource source;

    void quiet() {
        when(source.stuck(any(), any(), any())).thenReturn(new HealthSource.StuckData(2, Instant.now().minus(Duration.ofMinutes(50)), 0, 0, 0, 0));
        when(source.statusCounts(any())).thenReturn(List.of(new HealthSource.EventStatusCount("GILI", "policy.activation.completed", "SENT_TO_CDS", 9),
                new HealthSource.EventStatusCount("GILI", "policy.activation.completed", "TECHNICAL_FAILURE", 1)));
        when(source.topFailureWorkers(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(source.retryStatuses()).thenReturn(Map.of());
        when(source.deadLetters()).thenReturn(new HealthSource.DeadLetterData(Map.of(), null));
        when(source.funnel(any())).thenReturn(new HealthSource.FunnelData(0, 0, 0, 0, List.of()));
        when(source.newEventTypes(any(), any())).thenReturn(List.of(new HealthSource.NewEventType("GILI", "brand.new.event",
                Instant.parse("2026-10-20T10:00:00Z"))));
        when(source.lastIncoming()).thenReturn(Instant.now());
    }

    @Test
    void healthEndpointReturnsTheReportWithSnakeCaseKeysAndTheWiredEnvironment() throws Exception {
        quiet();

        mvc.perform(get("/api/health?windowHours=24")).andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("dev"))
                .andExpect(jsonPath("$.report.window_hours").value(24))
                .andExpect(jsonPath("$.report.level").value("warn"))
                .andExpect(jsonPath("$.report.stuck.incoming_stuck").value(2))
                .andExpect(jsonPath("$.report.stuck.threshold_minutes").value(30))
                .andExpect(jsonPath("$.report.failures.by_event[0].event_type").value("policy.activation.completed"))
                .andExpect(jsonPath("$.report.failures.failure_rate").value(0.1))
                .andExpect(jsonPath("$.report.dead_letters.pending").value(0))
                .andExpect(jsonPath("$.report.new_event_types[0].event_type").value("brand.new.event"))
                .andExpect(jsonPath("$.report.new_event_types[0].first_seen").value("2026-10-20T10:00:00Z"));
    }

    @Test
    void snapshotsAreStoredAndServedAsHistory() throws Exception {
        quiet();
        HealthReport report = service.report(source, Instant.now(), 24, 30);

        int stored = recorder.record(report, "dev");

        assertThat(stored).isGreaterThanOrEqualTo(8);
        assertThat(jdbc.sql("SELECT metric_value FROM health_snapshot WHERE metric_key = 'STUCK_INCOMING'")
                .query(Double.class).single()).isEqualTo(2.0);
        mvc.perform(get("/api/health/history?metric=FAILURE_RATE_24H&hours=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].metric_value").value(0.1));
        assertThat(recorder.record(report, "nope")).as("unknown environment stores nothing").isZero();
        jdbc.sql("DELETE FROM health_snapshot").update();
        assertThat(OffsetDateTime.now()).isNotNull();
    }

    @Test
    void aBrokenDatabaseGivesAReadableErrorNotAStackTrace() throws Exception {
        quiet();
        when(source.retryStatuses()).thenThrow(new IllegalStateException("connection timed out"));

        mvc.perform(get("/api/health?windowHours=5")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errors[0]").value("Could not read the NGC database: connection timed out"));
    }

    @Test
    void repeatedRequestsWithinTwentySecondsReuseTheComputedReport() throws Exception {
        quiet();

        mvc.perform(get("/api/health?windowHours=11")).andExpect(status().isOk());
        mvc.perform(get("/api/health?windowHours=11")).andExpect(status().isOk());

        org.mockito.Mockito.verify(source, org.mockito.Mockito.times(1)).lastIncoming();
    }
}
