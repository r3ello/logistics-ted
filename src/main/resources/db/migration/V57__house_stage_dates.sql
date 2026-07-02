-- Per-stage start/end dates (the Начална Дата / Крайна Дата columns of the crew-leader worklist).
-- Set when a crew leader marks a stage started / finished.
ALTER TABLE house_stage ADD COLUMN IF NOT EXISTS start_date DATE;
ALTER TABLE house_stage ADD COLUMN IF NOT EXISTS end_date   DATE;
