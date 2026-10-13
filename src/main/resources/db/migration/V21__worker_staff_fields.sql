-- ============================================================
--  V21 — staff master sheet: worker/crew columns for the `workers` CSV importer
--
--  The client's staff sheet (Персонал_Мастър) is the source for crews and workers. Each person
--  carries a stable id (`FR001`, `RO012`, ...) that becomes worker.external_id, shown in the app
--  and kept in lockstep with import_ref the same way house.external_id is (V17).
--
--  A crew is identified in the sheet by (group, leader nickname) — `Конструкция` + `Влади`. Neither
--  is unique on its own (`Любо` leads one crew on the roof and another on insulation), so the pair
--  is the crew's natural key. crew.name stays the leader's full name, as the UI has always shown it.
--
--  id_card_number is personal data: admin-only in the API, never logged, never exported.
--  The sheet's social-insurance amounts (Осигурен / от Фирма / от Служител) are deliberately NOT
--  stored — the client is removing those columns.
-- ============================================================

ALTER TABLE worker ADD COLUMN IF NOT EXISTS external_id     VARCHAR(40);
ALTER TABLE worker ADD COLUMN IF NOT EXISTS employment_type VARCHAR(20);
ALTER TABLE worker ADD COLUMN IF NOT EXISTS company         VARCHAR(150);
ALTER TABLE worker ADD COLUMN IF NOT EXISTS photo_url       VARCHAR(512);
ALTER TABLE worker ADD COLUMN IF NOT EXISTS hire_date       DATE;
ALTER TABLE worker ADD COLUMN IF NOT EXISTS id_card_number  VARCHAR(40);
ALTER TABLE worker ADD COLUMN IF NOT EXISTS clothing_size   VARCHAR(20);
ALTER TABLE worker ADD COLUMN IF NOT EXISTS clothing_issued VARCHAR(255);

ALTER TABLE worker DROP CONSTRAINT IF EXISTS chk_worker_employment_type;
ALTER TABLE worker ADD CONSTRAINT chk_worker_employment_type
    CHECK (employment_type IS NULL OR employment_type IN ('INTERNAL', 'EXTERNAL', 'SUBCONTRACTOR'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_worker_external_id
    ON worker (external_id)
 WHERE external_id IS NOT NULL;

ALTER TABLE crew ADD COLUMN IF NOT EXISTS stage_group VARCHAR(100);
ALTER TABLE crew ADD COLUMN IF NOT EXISTS nickname    VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_crew_group_nickname
    ON crew (stage_group, nickname)
 WHERE stage_group IS NOT NULL AND nickname IS NOT NULL;
