-- Test house for check-in/out QR testing
INSERT INTO house (name, location, lat, lng)
VALUES ('Test House', 'My Location', 42.845765, 23.228134)
ON CONFLICT DO NOTHING;

-- Assign QR token to the test house
UPDATE house
SET checkin_token = md5(CAST(id AS TEXT) || '-checkin-tedhouse-2026') || md5(CAST(id AS TEXT) || '-salt2')
WHERE name = 'Test House' AND checkin_token IS NULL;

-- Test crew assigned to test house
INSERT INTO crew (name, house_id)
SELECT 'Test Crew', id FROM house WHERE name = 'Test House'
ON CONFLICT DO NOTHING;

-- Test worker assigned to test crew
INSERT INTO worker (name, role, crew_id, location, lat, lng)
SELECT 'Test Worker', 'CREW_MEMBER', c.id, 'My Location', 42.845765, 23.228134
FROM crew c WHERE c.name = 'Test Crew'
ON CONFLICT DO NOTHING;
