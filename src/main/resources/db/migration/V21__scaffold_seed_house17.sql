-- House 17 (House test) was seeded in V2 but missed in V20 scaffold seed.
-- Safe to run on existing DBs — skips if scaffold already exists for this house.
INSERT INTO scaffold (status, start_date, end_date, house_id)
SELECT 'NONE', NULL, NULL, 17
WHERE NOT EXISTS (SELECT 1 FROM scaffold WHERE house_id = 17);
