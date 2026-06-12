-- Seed scaffold records for the base houses (ids 1-10).
-- Only inserts where no scaffold row already exists for the house,
-- so this is safe to run against both fresh and existing databases.

INSERT INTO scaffold (status, start_date, end_date, house_id)
SELECT v.status::VARCHAR, v.start_date::DATE, v.end_date::DATE, v.house_id
FROM (VALUES
  ('IN_USE',    '2026-05-20', '2026-06-30',  1),
  ('AVAILABLE', '2026-07-01', '2026-09-15',  2),
  ('NONE',      NULL,          NULL,          3),
  ('IN_USE',    '2026-05-15', '2026-06-20',  4),
  ('NONE',      NULL,          NULL,          6),
  ('AVAILABLE', '2026-07-01', '2026-09-01',  7),
  ('AVAILABLE', NULL,          NULL,          8),
  ('NONE',      NULL,          NULL,          9),
  ('NONE',      NULL,          NULL,         10)
) AS v(status, start_date, end_date, house_id)
WHERE NOT EXISTS (
  SELECT 1 FROM scaffold s WHERE s.house_id = v.house_id
);
