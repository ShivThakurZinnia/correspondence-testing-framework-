package com.zinnia.regression.collector;

import com.zinnia.regression.collector.NgcView.IncomingEventView;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists what the collectors saw against a run case, adding only records not already stored. */
@Service
public class ObservationRecorder {

    private final JdbcClient jdbc;

    public ObservationRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return number of new observations stored */
    @Transactional
    public int record(long runCaseId, String correlationId, NgcView view) {
        Set<String> known = new HashSet<>(jdbc.sql("""
                SELECT collection || '|' || source_document_id || '|' || COALESCE(status, '') FROM run_observation
                WHERE run_case_id = :r AND source_document_id IS NOT NULL
                """).param("r", runCaseId).query(String.class).list());
        int added = 0;
        for (Observation o : view.observations()) {
            // a document is stored again only when its status changed, so the timeline shows transitions
            String key = o.collection() + "|" + o.sourceDocumentId() + "|" + (o.status() == null ? "" : o.status());
            if (o.sourceDocumentId() != null && !known.add(key)) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO run_observation (run_case_id, occurred_at, source, collection, event_type, status,
                      target_role, detail_json, correlation_id, ngc_unique_id, tango_job_id, role_tracking_id,
                      original_target_event, source_document_id)
                    VALUES (:r, :at, :src, :col, :ev, :st, :role, :detail, :corr, :ngc, :tango, :rt, :orig, :doc)
                    """)
                    .param("r", runCaseId).param("at", o.occurredAt()).param("src", o.source())
                    .param("col", o.collection()).param("ev", o.eventType()).param("st", o.status())
                    .param("role", o.targetRole()).param("detail", o.detailJson()).param("corr", correlationId)
                    .param("ngc", o.ngcUniqueId()).param("tango", o.tangoJobId()).param("rt", o.roleTrackingId())
                    .param("orig", o.originalTargetEvent()).param("doc", o.sourceDocumentId()).update();
            added++;
        }
        IncomingEventView latest = view.incomingEvents().isEmpty() ? null
                : view.incomingEvents().get(view.incomingEvents().size() - 1);
        if (latest != null) {
            jdbc.sql("UPDATE test_run_case SET ngc_unique_id = :u, final_incoming_status = :s WHERE id = :r")
                    .param("u", latest.uniqueId()).param("s", latest.status()).param("r", runCaseId).update();
        }
        return added;
    }
}
