-- house gains the project-level columns of the client's ACTIVE_MASTER sheet, and `address` becomes
-- optional.
--
-- WHY. The client now syncs from ACTIVE_MASTER (examples/TH_ACTIVE_MASTER - ACTIVE_MASTER.csv), keyed
-- by its TH id (Project_ID). That sheet has no address column at all, so a NOT NULL address would
-- reject every row. The user chose to store every project-level column the sheet has:
--
--   Client_Name       -> client_name          the end client (personal data: a name only, no ЕГН/phone)
--   Project_Link      -> drive_folder_url     the project's Google Drive folder
--   Google_Chat_ID    -> google_chat_id       "spaces/AAQA…"
--   Google_Album_ID   -> google_album_id      Google Photos album id
--   Google_Album_URL  -> google_album_url
--   Calculator_SS_Id  -> calculator_sheet_id  Google Sheets ids; the UI builds the link
--   Prj_Master_SS_Id  -> master_sheet_id
--
-- Project_ID -> external_id, Project_Name -> name, Location -> location already exist. The 26 stage
-- columns belong to house_stage, not here. The sheet's unnamed second column ("📸" / "Завършен") has
-- no agreed meaning yet and is not stored.

ALTER TABLE public.house ALTER COLUMN address DROP NOT NULL;

ALTER TABLE public.house ADD COLUMN client_name         character varying(255);
ALTER TABLE public.house ADD COLUMN drive_folder_url    character varying(512);
ALTER TABLE public.house ADD COLUMN google_chat_id      character varying(120);
ALTER TABLE public.house ADD COLUMN google_album_id     character varying(255);
ALTER TABLE public.house ADD COLUMN google_album_url    character varying(512);
ALTER TABLE public.house ADD COLUMN calculator_sheet_id character varying(120);
ALTER TABLE public.house ADD COLUMN master_sheet_id     character varying(120);
