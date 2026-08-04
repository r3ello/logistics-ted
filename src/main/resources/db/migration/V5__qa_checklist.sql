CREATE TABLE qa_checklist_item (
    id          SERIAL       PRIMARY KEY,
    stage_order INTEGER      NOT NULL REFERENCES stage_type(stage_order) ON DELETE CASCADE,
    item_text_bg VARCHAR(300) NOT NULL,
    item_text_en VARCHAR(300) NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_qa_checklist_stage ON qa_checklist_item(stage_order, sort_order);

-- Seed menu_config entries so the QA nav group is visible from first deploy
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key) VALUES
('nav_group_qa',        'nav', 'Quality Assurance', 'Контрол на качеството', '✅', true, 9,  true,  NULL),
('nav_qa_checklists',   'nav', 'Stage Checklists',  'Контролни списъци',     '📋', true, 1,  false, 'nav_group_qa');
