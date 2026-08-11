-- house gains `address`, and `location` changes meaning.
--
-- WHY. The client's CRM export (examples/house-example.csv) carries both a textual address
-- (`Address`, e.g. "Рударци") and a Google Maps short link (`Location`, e.g.
-- https://maps.app.goo.gl/nRDmHHrUMxXvW9FXA). Until now `house.location` held the address text and
-- the link had nowhere to go, so the CSV import dropped it with an UNKNOWN_COLUMN warning. We adopt
-- the CRM's own vocabulary: `address` is the text, `location` is the link.
--
-- The existing values in `location` ARE addresses, so they move to `address` rather than being
-- re-typed. `location` is then emptied: leaving address text in a column the app now renders as a
-- hyperlink would produce broken links, and a half-migrated column is worse than an empty one — the
-- next import run refills it from the sheet.
--
-- NOTE. The link is stored, never resolved. Turning a short link into lat/lng needs an HTTP redirect
-- follow, which must not happen inside an import (DATA_IMPORT_PLAN.md §7); coordinates keep coming
-- from the map picker.

ALTER TABLE public.house ADD COLUMN address character varying(255);

UPDATE public.house SET address = location;

ALTER TABLE public.house ALTER COLUMN address SET NOT NULL;

-- Maps URLs are longer than an address line, and a house may simply not have one yet.
ALTER TABLE public.house ALTER COLUMN location TYPE character varying(512);
ALTER TABLE public.house ALTER COLUMN location DROP NOT NULL;

UPDATE public.house SET location = NULL;
