-- Update current_phase to concatenate all IN_PROGRESS stage names (ordered)
UPDATE house h
SET current_phase = sub.phases
FROM (
    SELECT house_id,
           string_agg(stage_name, ', ' ORDER BY stage_order) AS phases
    FROM house_stage
    WHERE status = 'IN_PROGRESS'
    GROUP BY house_id
) sub
WHERE h.id = sub.house_id;

-- Clear phase for houses with no IN_PROGRESS stages
UPDATE house
SET current_phase = NULL
WHERE id NOT IN (
    SELECT DISTINCT house_id FROM house_stage WHERE status = 'IN_PROGRESS'
) AND current_phase IS NOT NULL;
