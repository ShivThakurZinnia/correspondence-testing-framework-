-- Keep the schedule's name on each run so history still shows it after the schedule is deleted.
ALTER TABLE test_run ADD COLUMN schedule_name VARCHAR(160);
UPDATE test_run SET schedule_name = (SELECT s.name FROM schedule s WHERE s.id = test_run.schedule_id)
WHERE schedule_id IS NOT NULL;
