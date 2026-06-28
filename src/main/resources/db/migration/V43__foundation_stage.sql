-- Insert Foundation stage for all existing houses
INSERT INTO house_stage (house_id, stage_order, stage_name, stage_name_en, status, updated_at)
SELECT id, 0, 'Фундамент', 'Foundation', 'NOT_STARTED', NOW()
FROM house;

-- Create 3 crews for Foundation stage
INSERT INTO crew (name, house_id) VALUES
  ('Foundation Crew A', NULL),
  ('Foundation Crew B', NULL),
  ('Foundation Crew C', NULL);

-- Add crew leaders for each foundation crew
INSERT INTO worker (name, role, crew_id)
SELECT 'Leader - ' || c.name, 'CREW_LEADER', c.id
FROM crew c
WHERE c.name IN ('Foundation Crew A', 'Foundation Crew B', 'Foundation Crew C');

-- Add members for each foundation crew
INSERT INTO worker (name, role, crew_id)
SELECT 'Member 1 - ' || c.name, 'CREW_MEMBER', c.id
FROM crew c
WHERE c.name IN ('Foundation Crew A', 'Foundation Crew B', 'Foundation Crew C');

INSERT INTO worker (name, role, crew_id)
SELECT 'Member 2 - ' || c.name, 'CREW_MEMBER', c.id
FROM crew c
WHERE c.name IN ('Foundation Crew A', 'Foundation Crew B', 'Foundation Crew C');

-- Link foundation crews to stage_type_crew for stage_order=0
INSERT INTO stage_type_crew (stage_order, crew_id)
SELECT 0, id FROM crew WHERE name IN ('Foundation Crew A', 'Foundation Crew B', 'Foundation Crew C');
