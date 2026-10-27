package com.zinnia.regression.run;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk runs produce roughly twenty detail rows per event. Detail (observations, assertions, stored XML and injected
 * payloads) is dropped after {@code detail-days}; the run and its per-event result stay for {@code summary-days}.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient jdbc;
    private final int detailDays;
    private final int summaryDays;

    public RetentionJob(JdbcClient jdbc, @Value("${regression.retention.detail-days:30}") int detailDays,
                        @Value("${regression.retention.summary-days:365}") int summaryDays) {
        this.jdbc = jdbc;
        this.detailDays = detailDays;
        this.summaryDays = summaryDays;
    }

    public record Result(int runsPurged, int runsDeleted) {
    }

    @Scheduled(cron = "${regression.retention.cron:0 15 3 * * *}")
    void scheduled() {
        try {
            Result r = purge(OffsetDateTime.now(ZoneOffset.UTC));
            log.info("Retention: dropped detail of {} runs, deleted {} old runs", r.runsPurged(), r.runsDeleted());
        } catch (RuntimeException e) {
            log.warn("Retention failed: {}", e.getMessage());
        }
    }

    @Transactional
    public Result purge(OffsetDateTime now) {
        OffsetDateTime detailCutoff = now.minusDays(detailDays);
        List<Long> runs = jdbc.sql("""
                SELECT id FROM test_run WHERE details_purged = FALSE AND finished_at < :c AND status NOT IN ('QUEUED','RUNNING')
                """).param("c", detailCutoff).query(Long.class).list();
        for (Long run : runs) {
            String cases = "SELECT id FROM test_run_case WHERE run_id = :r";
            List<Long> artifactIds = jdbc.sql("""
                    SELECT artifact_id FROM run_case_artifact WHERE run_case_id IN (%s)
                    UNION SELECT injected_payload_artifact_id FROM test_run_case
                      WHERE run_id = :r AND injected_payload_artifact_id IS NOT NULL
                    """.formatted(cases)).param("r", run).query(Long.class).list();
            jdbc.sql("DELETE FROM assertion_result WHERE run_case_id IN (" + cases + ")").param("r", run).update();
            jdbc.sql("DELETE FROM run_observation WHERE run_case_id IN (" + cases + ")").param("r", run).update();
            jdbc.sql("DELETE FROM run_case_artifact WHERE run_case_id IN (" + cases + ")").param("r", run).update();
            jdbc.sql("UPDATE test_run_case SET injected_payload_artifact_id = NULL WHERE run_id = :r").param("r", run).update();
            for (Long a : artifactIds) {
                jdbc.sql("DELETE FROM artifact WHERE id = :a AND kind IN ('RUN_XML','CDS_PAYLOAD','MONGO_SNAPSHOT') "
                        + "OR (id = :a AND kind = 'EVENT_PAYLOAD' AND name LIKE 'injected-%')").param("a", a).update();
            }
            jdbc.sql("UPDATE test_run SET details_purged = TRUE WHERE id = :r").param("r", run).update();
        }

        OffsetDateTime summaryCutoff = now.minusDays(summaryDays);
        List<Long> old = jdbc.sql("SELECT id FROM test_run WHERE finished_at < :c AND status NOT IN ('QUEUED','RUNNING')")
                .param("c", summaryCutoff).query(Long.class).list();
        for (Long run : old) {
            jdbc.sql("UPDATE test_run SET rerun_of_run_id = NULL WHERE rerun_of_run_id = :r").param("r", run).update();
            jdbc.sql("DELETE FROM test_run_case WHERE run_id = :r").param("r", run).update();
            jdbc.sql("DELETE FROM test_run WHERE id = :r").param("r", run).update();
        }
        return new Result(runs.size(), old.size());
    }
}
