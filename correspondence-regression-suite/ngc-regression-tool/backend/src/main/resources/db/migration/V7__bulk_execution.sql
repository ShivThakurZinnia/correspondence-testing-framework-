-- Bulk execution: per-run throttling, cancellation, re-runs, per-policy ordering.
ALTER TABLE test_run ADD COLUMN max_in_flight INT;                 -- NULL = service default
ALTER TABLE test_run ADD COLUMN events_per_second DOUBLE PRECISION; -- NULL = service default
ALTER TABLE test_run ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE test_run ADD COLUMN rerun_of_run_id BIGINT REFERENCES test_run(id);
ALTER TABLE test_run ADD COLUMN details_purged BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE test_run_case ADD COLUMN serial_key VARCHAR(120);       -- policy number: one in-flight event per key

ALTER TABLE schedule ADD COLUMN max_in_flight INT;
ALTER TABLE schedule ADD COLUMN events_per_second DOUBLE PRECISION;

CREATE INDEX ix_run_case_run_status ON test_run_case(run_id, status);
CREATE INDEX ix_run_started ON test_run(status, queued_at);
