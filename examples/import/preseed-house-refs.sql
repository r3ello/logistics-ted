-- Pre-seed import_ref for houses that ALREADY exist in the app.
--
-- WHY. Rows are matched by external key only — there is no matching by name, because house.name is
-- not unique (DATA_IMPORT_PLAN.md §0.1). Without these mappings the first `apply` of houses.csv
-- would CREATE a duplicate of every existing house, each dragging along its own warehouse, 27
-- house_stage rows, check-in token and doc-folder tree. Run this ONCE, BEFORE the first apply.
--
-- HOW. Edit the list in step 1: one row per house that exists in both the sheet and the app,
-- pairing the sheet's CRM_ID with our house.id. Verify the pairing by hand first — this is the one
-- step with no automatic defence, since a wrong pairing silently syncs the wrong record forever:
--
--     SELECT id, name, address FROM house ORDER BY id;
--
-- Houses that exist ONLY in the sheet need no row here — the import creates them properly.
--
-- Snapshots/baselines are deliberately NOT seeded. ImportRef.hasBaseline() is then false, the merge
-- takes its no-baseline path and treats the sheet as authoritative for that first run: every
-- difference shows up as a plain `updated` (visible under mode=validate before anything is written),
-- and the first apply establishes both baselines.
--
-- AFTERWARDS, dry-run and read the report:
--     POST /api/import/houses?mode=validate   →  pre-seeded rows must come back as
--                                                `updated` / `unchanged`, never `created`.

BEGIN;

-- 1) EDIT THIS LIST. -1 is a deliberate tripwire: the script refuses to run until it is gone.
CREATE TEMP TABLE house_key_map (external_key text, house_id int) ON COMMIT DROP;

INSERT INTO house_key_map (external_key, house_id) VALUES
    ('CRM-2026-00010', -1),   -- ← house.id of "Рударци Йордан"
    ('CRM-2026-00011', -1),   -- ← house.id of "Пожарево Пламен"
    ('CRM-2026-00012', -1);   -- ← house.id of "Храбърско Йордан"

-- 2) Tripwire. Aborts the whole transaction on a leftover placeholder or an id that is not a live
--    house — a mapping pointing at nothing would be re-created on the first run and mask the error.
DO $$
DECLARE bad int;
BEGIN
    SELECT count(*) INTO bad
      FROM house_key_map m
     WHERE m.house_id <= 0
        OR NOT EXISTS (SELECT 1 FROM house h WHERE h.id = m.house_id);
    IF bad > 0 THEN
        RAISE EXCEPTION
            'house_key_map has % row(s) with a placeholder or unknown house id — edit the list first', bad;
    END IF;
END $$;

-- 3) Map. ON CONFLICT DO NOTHING makes the script safe to re-run: an existing mapping is never
--    re-pointed here, since that is exactly the silent-overwrite failure mode we are guarding.
INSERT INTO import_ref (entity_type, external_key, entity_id)
SELECT 'house', external_key, house_id FROM house_key_map
ON CONFLICT (entity_type, external_key) DO NOTHING;

COMMIT;

-- 4) Verify: every mapping must name the house you expect.
SELECT r.external_key, r.entity_id, h.name, h.address
  FROM import_ref r
  LEFT JOIN house h ON h.id = r.entity_id
 WHERE r.entity_type = 'house'
 ORDER BY r.external_key;
