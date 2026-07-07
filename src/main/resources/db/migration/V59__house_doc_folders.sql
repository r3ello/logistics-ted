-- Backfill: create a doc_folder under dept '02' for every existing house
INSERT INTO doc_folder (code, label_en, label_bg, icon, color, sort_order, parent_id)
SELECT
    'house_' || h.id,
    h.name,
    h.name,
    '🏠',
    '#f97316',
    h.id,
    (SELECT id FROM doc_folder WHERE code = '02' AND parent_id IS NULL)
FROM house h
WHERE 'house_' || h.id NOT IN (
    SELECT code FROM doc_folder
    WHERE parent_id = (SELECT id FROM doc_folder WHERE code = '02' AND parent_id IS NULL)
);
