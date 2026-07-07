-- stage_type_crew is redundant with crew.stage_order (added V49).
-- All queries now join through crew.stage_order directly.
DROP TABLE IF EXISTS stage_type_crew;
