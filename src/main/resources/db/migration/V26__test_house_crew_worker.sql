-- Test house for check-in/out QR testing
INSERT INTO house (name, location, lat, lng)
SELECT 'Test House', 'My Location', 42.845765, 23.228134
WHERE NOT EXISTS (SELECT 1 FROM house WHERE name = 'Test House');

-- Assign QR token to the test house
UPDATE house
SET checkin_token = md5(CAST(id AS TEXT) || '-checkin-tedhouse-2026') || md5(CAST(id AS TEXT) || '-salt2')
WHERE name = 'Test House' AND checkin_token IS NULL;

-- Test crew assigned to test house
INSERT INTO crew (name, house_id)
SELECT 'Test Crew', id FROM house WHERE name = 'Test House'
AND NOT EXISTS (SELECT 1 FROM crew WHERE name = 'Test Crew');

-- Test worker assigned to test crew (only if not already there)
INSERT INTO worker (name, role, crew_id, location, lat, lng)
SELECT 'Test Worker', 'CREW_MEMBER', c.id, 'My Location', 42.845765, 23.228134
FROM crew c WHERE c.name = 'Test Crew'
  AND NOT EXISTS (
    SELECT 1 FROM worker w WHERE w.crew_id = c.id AND w.name = 'Test Worker'
  );
