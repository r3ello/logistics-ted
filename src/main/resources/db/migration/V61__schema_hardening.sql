-- ── 1. Drop ghost worker.house_id column ─────────────────────────────────────
ALTER TABLE worker DROP COLUMN IF EXISTS house_id;

-- ── 3. Canonical stage_type table + FK from crew.stage_order ─────────────────
CREATE TABLE IF NOT EXISTS stage_type (
    stage_order  INTEGER      PRIMARY KEY,
    stage_name   VARCHAR(100) NOT NULL,
    stage_name_en VARCHAR(100)
);

INSERT INTO stage_type (stage_order, stage_name, stage_name_en)
SELECT DISTINCT stage_order, stage_name, stage_name_en
FROM house_stage
ON CONFLICT (stage_order) DO NOTHING;

ALTER TABLE crew
    ADD CONSTRAINT fk_crew_stage_order
    FOREIGN KEY (stage_order) REFERENCES stage_type(stage_order)
    ON DELETE SET NULL ON UPDATE CASCADE
    DEFERRABLE INITIALLY DEFERRED;

-- ── 4. Missing indexes on frequently-queried FK columns ───────────────────────
CREATE INDEX IF NOT EXISTS idx_worker_crew_id    ON worker(crew_id);
CREATE INDEX IF NOT EXISTS idx_worker_trade      ON worker(trade);
CREATE INDEX IF NOT EXISTS idx_house_stage_crew  ON house_stage(crew_id);
CREATE INDEX IF NOT EXISTS idx_crew_house_id     ON crew(house_id);

-- ── 5 (was 7). CHECK quantity > 0 on material_order_item ─────────────────────
ALTER TABLE material_order_item
    ADD CONSTRAINT chk_order_item_qty_positive CHECK (quantity > 0);
