-- A run or schedule selects test cases by tag across every module, so it no longer belongs to one module version.
ALTER TABLE test_run ALTER COLUMN module_version_id DROP NOT NULL;
ALTER TABLE schedule ALTER COLUMN module_id DROP NOT NULL;
