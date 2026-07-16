-- ────────────────────────────────────────────────────────────
-- V2: Replace code-based doc_folder routing with folder_type enum
--     and drop code column from doc_folder_template
-- ────────────────────────────────────────────────────────────

-- 1. Add folder_type to doc_folder
ALTER TABLE public.doc_folder ADD COLUMN folder_type VARCHAR(50);

UPDATE public.doc_folder
SET folder_type = 'ACTIVE_SITES'
WHERE code = '02' AND parent_id IS NULL;

UPDATE public.doc_folder
SET folder_type = 'COMPLETED_SITES'
WHERE code = '07' AND parent_id IS NULL;

-- 2. Drop code from doc_folder_template
ALTER TABLE public.doc_folder_template
    DROP CONSTRAINT IF EXISTS doc_folder_template_code_unique;

ALTER TABLE public.doc_folder_template DROP COLUMN IF EXISTS code;
