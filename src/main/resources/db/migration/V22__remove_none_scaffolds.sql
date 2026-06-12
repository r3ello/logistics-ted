-- Scaffold records with status='NONE' should not exist.
-- A scaffold is a physical entity — if it doesn't exist, there is no row.
-- NONE was a legacy concept from when scaffold was a column on the house table (V5/V6).
-- V20 and V21 incorrectly seeded NONE rows; this cleans them up.
DELETE FROM scaffold WHERE status = 'NONE';
