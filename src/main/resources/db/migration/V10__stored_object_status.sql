-- Adds the upload lifecycle flag to stored_object.
--
-- WHY IT IS NOT IN V9. V9 shipped when uploads were proxied through the application: the row was
-- written after the bytes had already landed, so every row was complete by construction. Uploads now
-- go straight from the browser to the bucket (presigned PUT), which means the row must exist BEFORE
-- the bytes do — so a row needs to say whether it is backed by an object yet.
--
--   'pending' — the upload URL was issued; nothing may be assumed about the object.
--   'ready'   — the bucket was asked (HEAD) and confirmed what arrived.
--
-- Listings show 'ready' only (StoredObjectRepository.findReadyByFolderId), so an upload that never
-- finishes — a tab closed mid-transfer — is invisible rather than a file entry that 404s on click.
-- The pending row still carries object_key, which is what makes such an orphan findable later.
--
-- DEFAULT 'ready' is correct for rows already in the table: they were written by the old proxied
-- path, which only inserted after a successful PUT.

ALTER TABLE public.stored_object
    ADD COLUMN status varchar(10) NOT NULL DEFAULT 'ready';

ALTER TABLE public.stored_object
    ADD CONSTRAINT chk_stored_object_status CHECK (status IN ('pending', 'ready'));
