-- Clear crew.house_id for crews that have no IN_PROGRESS stages on any house.
-- Crews with only DONE or NOT_STARTED stages should not appear as assigned to a house.
UPDATE crew
SET house_id = NULL
WHERE id NOT IN (
    SELECT DISTINCT crew_id
    FROM house_stage
    WHERE crew_id IS NOT NULL
      AND status = 'IN_PROGRESS'
);
