-- Extend app_user.role column to fit 'qa_inspector' (12 chars)
ALTER TABLE app_user ALTER COLUMN role TYPE VARCHAR(20);
