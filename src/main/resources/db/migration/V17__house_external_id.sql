-- ============================================================
--  V17 — house.external_id: the client's own id for a house, visible and searchable in the app
--
--  Until now the external key lived only in import_ref (the CSV sync's private state). The client
--  knows their houses by that id and wants to see, search and filter by it — and to type it in
--  when creating a house by hand, so a later import updates that house instead of duplicating it.
--
--  house.external_id is the business identifier; import_ref stays the sync's merge state. The two
--  are kept in lockstep by the app: the import writes the key on create, HouseService re-keys
--  import_ref when the id is edited, and the importer adopts a hand-made house whose external_id
--  matches an incoming key (instead of creating a duplicate).
-- ============================================================

ALTER TABLE house ADD COLUMN IF NOT EXISTS external_id VARCHAR(120);

-- Backfill from the sync mappings. import_ref is unique on (entity_type, external_key) but not on
-- entity_id, so a house could in principle carry two keys (e.g. a hand-edited pre-seed). Pick one
-- deterministically — the oldest mapping — rather than failing the migration.
UPDATE house h
   SET external_id = r.external_key
  FROM (
        SELECT DISTINCT ON (entity_id) entity_id, external_key
          FROM import_ref
         WHERE entity_type = 'house'
         ORDER BY entity_id, id
       ) r
 WHERE h.id = r.entity_id
   AND h.external_id IS NULL;

-- One house per external id. Partial, so any number of houses may have none.
CREATE UNIQUE INDEX IF NOT EXISTS uq_house_external_id
    ON house (external_id)
 WHERE external_id IS NOT NULL;
