-- Remove foundation crews and their workers/stage links
DELETE FROM stage_type_crew WHERE stage_order = 0;
DELETE FROM worker WHERE crew_id IN (SELECT id FROM crew WHERE name LIKE 'Foundation Crew%');
DELETE FROM crew WHERE name LIKE 'Foundation Crew%';
