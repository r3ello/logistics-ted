-- ============================================================
--  V23 — three stages gain crews (client's answers, 2026-10-15)
--
--  * Ниво замазка (8)  — its own crew group: Паскалев (SL001, CrewID CRSL001).
--  * Мълниезащита (24) — worked by the electricians: joins the Ел group.
--  * Първази (26)      — worked by the laminate crew: joins the Ламинат group.
--
--  main_stage_bg is the crew group the `workers` and `house-stages` importers match on, so this
--  is what lets a staff row say `Ниво Замазка` and a stage cell `Паскалев изпълнил` link to a crew.
--
--  Existing crews keep their stage list (crew_stage_type), and the matrix only offers a crew on
--  the stages in that list, so the crews already working the group's stages (11 for Ел, 22 for
--  Ламинат) get the new stage, and so do their members (worker_stage_type mirrors the crew's, as
--  CrewMembershipService.join copies it). Keyed on the stage rather than crew.stage_group so crews
--  made by hand are included too.
-- ============================================================

UPDATE stage_type SET main_stage_bg = 'Ниво замазка', main_stage_en = 'Screed Level', has_crew = true
 WHERE stage_order = 8;
UPDATE stage_type SET main_stage_bg = 'Ел',           main_stage_en = 'Electrical',   has_crew = true
 WHERE stage_order = 24;
UPDATE stage_type SET main_stage_bg = 'Ламинат',      main_stage_en = 'Laminate',     has_crew = true
 WHERE stage_order = 26;

INSERT INTO crew_stage_type (crew_id, stage_order)
SELECT DISTINCT cst.crew_id, x.new_order
  FROM crew_stage_type cst
  JOIN (VALUES (11, 24), (22, 26)) AS x(existing_order, new_order) ON x.existing_order = cst.stage_order
 WHERE NOT EXISTS (SELECT 1 FROM crew_stage_type e
                    WHERE e.crew_id = cst.crew_id AND e.stage_order = x.new_order);

INSERT INTO worker_stage_type (worker_id, stage_order)
SELECT DISTINCT w.id, x.new_order
  FROM worker w
  JOIN crew_stage_type cst ON cst.crew_id = w.crew_id
  JOIN (VALUES (11, 24), (22, 26)) AS x(existing_order, new_order) ON x.existing_order = cst.stage_order
 WHERE NOT EXISTS (SELECT 1 FROM worker_stage_type e
                    WHERE e.worker_id = w.id AND e.stage_order = x.new_order);
