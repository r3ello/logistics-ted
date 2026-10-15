-- ============================================================
--  V22 — crew.external_id: the staff sheet's CrewID (`CRIS006`)
--
--  Until now a crew was identified in the sheet only by (group, leader nickname) — V21. Renaming
--  the nickname in the sheet therefore made a new crew and orphaned every house_stage linked to the
--  old one. The client now gives each crew a stable CrewID on its leader's row; it becomes the
--  crew's identity for the `workers` import and the crew key of the `house-stages` sync, while
--  (stage_group, nickname) stays as the fallback for rows that do not carry it.
--
--  Nullable: crews made by hand, or never named by the sheet, have none.
-- ============================================================

ALTER TABLE crew ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

CREATE UNIQUE INDEX IF NOT EXISTS uq_crew_external_id
    ON crew (external_id)
 WHERE external_id IS NOT NULL;
