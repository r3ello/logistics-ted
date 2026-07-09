-- Add leader_id FK to crew, mirroring the existing manager_id pattern
ALTER TABLE crew ADD COLUMN leader_id INTEGER REFERENCES worker(id) ON DELETE SET NULL;

-- Backfill from existing worker assignments
UPDATE crew c
SET leader_id = (
    SELECT id FROM worker
    WHERE crew_id = c.id AND role = 'CREW_LEADER'
    LIMIT 1
);
