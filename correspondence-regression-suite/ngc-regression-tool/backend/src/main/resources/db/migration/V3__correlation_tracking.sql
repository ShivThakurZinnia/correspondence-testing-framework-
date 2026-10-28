-- Track every executed event by correlationId. A fresh UUID is generated per run case, registered here
-- BEFORE the Kafka publish, and every observation (NGC and CDS callbacks) is stored against it.

-- Detected once at upload time; the uploaded payload keeps its original id and is never edited.
ALTER TABLE test_case_version ADD COLUMN payload_correlation_id VARCHAR(80);

-- Exact payload published for this run (original id rewritten), kept for audit and replay.
ALTER TABLE test_run_case ADD COLUMN injected_payload_artifact_id BIGINT REFERENCES artifact(id);
ALTER TABLE test_run_case ADD COLUMN source_correlation_id VARCHAR(80);   -- id found in the uploaded payload, replaced everywhere
ALTER TABLE test_run_case ADD COLUMN published_at TIMESTAMP WITH TIME ZONE; -- set after Kafka ack; NULL = never injected
ALTER TABLE test_run_case ADD COLUMN kafka_partition INT;
ALTER TABLE test_run_case ADD COLUMN kafka_offset BIGINT;

ALTER TABLE run_observation ADD COLUMN correlation_id    VARCHAR(80);      -- denormalised for direct lookup by id
ALTER TABLE run_observation ADD COLUMN ngc_unique_id     VARCHAR(80);      -- identifiers.ngcUniqueId on callbacks
ALTER TABLE run_observation ADD COLUMN tango_job_id      VARCHAR(80);      -- identifiers.JobId
ALTER TABLE run_observation ADD COLUMN role_tracking_id  VARCHAR(80);
ALTER TABLE run_observation ADD COLUMN original_target_event VARCHAR(120); -- identifiers.originalTargetEvent
ALTER TABLE run_observation ADD COLUMN source_document_id VARCHAR(80);     -- Mongo _id / S3 key, to re-find the record

CREATE INDEX ix_obs_corr ON run_observation(correlation_id, occurred_at);
CREATE INDEX ix_run_case_ngc_unique ON test_run_case(ngc_unique_id);
