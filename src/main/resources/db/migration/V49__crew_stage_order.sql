-- Add stage_order directly to crew for quick lookup
ALTER TABLE crew ADD COLUMN IF NOT EXISTS stage_order integer;

-- Backfill from stage_type_crew (each crew maps to exactly one stage)
UPDATE crew c
SET stage_order = stc.stage_order
FROM stage_type_crew stc
WHERE stc.crew_id = c.id;
