-- Clear house_id from all crews that have no stage assignments
-- From now on house is derived from stage matrix assignments
UPDATE crew
SET house_id = NULL
WHERE id NOT IN (
    SELECT DISTINCT crew_id FROM house_stage WHERE crew_id IS NOT NULL
);
