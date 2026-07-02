-- Allow multiple check-in/out sessions per worker per house per day
ALTER TABLE work_session DROP CONSTRAINT IF EXISTS work_session_worker_id_house_id_session_date_key;
