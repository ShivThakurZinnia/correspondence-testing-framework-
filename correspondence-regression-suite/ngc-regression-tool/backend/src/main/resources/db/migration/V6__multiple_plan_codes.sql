-- A test case may list several plan codes. Each plan code is played as its own event (own correlationId) with the
-- same expected outcome. test_case_version.plan_code stays as the first code for older readers.
CREATE TABLE test_case_version_plan (
    test_case_version_id BIGINT      NOT NULL REFERENCES test_case_version(id),
    plan_code            VARCHAR(30) NOT NULL,
    position             INT         NOT NULL,
    PRIMARY KEY (test_case_version_id, plan_code)
);
INSERT INTO test_case_version_plan (test_case_version_id, plan_code, position)
SELECT id, plan_code, 0 FROM test_case_version WHERE plan_code IS NOT NULL;

ALTER TABLE test_run_case ADD COLUMN plan_code VARCHAR(30);
