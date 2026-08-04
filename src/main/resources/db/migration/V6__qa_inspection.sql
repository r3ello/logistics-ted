-- Extend app_user role to include qa_inspector
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS chk_app_user_role;
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_role CHECK (role IN ('admin','user','importer','qa_inspector'));

-- QA inspection tables and role

-- One inspection per house+stage, done by a qa_inspector after the stage is COMPLETED
CREATE TABLE qa_inspection (
    id              SERIAL       PRIMARY KEY,
    house_id        INTEGER      NOT NULL REFERENCES house(id) ON DELETE CASCADE,
    stage_order     INTEGER      NOT NULL REFERENCES stage_type(stage_order) ON DELETE CASCADE,
    inspector_id    INTEGER      NOT NULL REFERENCES app_user(id),
    inspected_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    result          VARCHAR(10)  NOT NULL CHECK (result IN ('PASS', 'FAIL')),
    notes           VARCHAR(500),
    UNIQUE (house_id, stage_order)
);

-- One row per checklist item per inspection — pass/fail + optional note
CREATE TABLE qa_inspection_item (
    id              SERIAL       PRIMARY KEY,
    inspection_id   INTEGER      NOT NULL REFERENCES qa_inspection(id) ON DELETE CASCADE,
    checklist_item_id INTEGER    NOT NULL REFERENCES qa_checklist_item(id) ON DELETE CASCADE,
    passed          BOOLEAN      NOT NULL,
    note            VARCHAR(300)
);

CREATE INDEX idx_qa_inspection_house  ON qa_inspection(house_id);
CREATE INDEX idx_qa_inspection_item   ON qa_inspection_item(inspection_id);
