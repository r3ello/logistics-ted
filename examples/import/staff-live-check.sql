-- Read-only checks for STAFF_MASTER_ANALYSIS.md: how far the live DB has drifted from the V1 seed.
-- SELECTs only. Run against tedhouse_logistic (dev) and tedhouse (prod).

-- 1. Totals and how much contact data is already filled in
SELECT count(*) AS workers,
       count(*) FILTER (WHERE crew_id IS NULL) AS no_crew,
       count(phone) AS phone, count(email) AS email,
       count(username) AS with_login, min(id), max(id)
FROM worker;

-- 2. Crews with leader, stages and member count (crew 103 = Евгени: roof stages in the seed?)
SELECT c.id, c.name, l.name AS leader, c.location,
       (SELECT string_agg(stage_order::text, ',' ORDER BY stage_order)
          FROM crew_stage_type s WHERE s.crew_id = c.id) AS stages,
       (SELECT count(*) FROM worker w WHERE w.crew_id = c.id) AS members
FROM crew c LEFT JOIN worker l ON l.id = c.leader_id
ORDER BY c.id;

-- 3. Workers added/edited after the seed (ids > 487, or contact data present)
SELECT id, name, role, crew_id, location, phone, email
FROM worker
WHERE id > 487 OR phone IS NOT NULL OR email IS NOT NULL
ORDER BY id;

-- 4. What hangs off workers/crews (why the import must never delete)
SELECT (SELECT count(*) FROM work_session)                       AS sessions,
       (SELECT count(DISTINCT worker_id) FROM work_session)      AS workers_with_sessions,
       (SELECT count(*) FROM house_stage WHERE crew_id IS NOT NULL) AS house_stages_with_crew,
       (SELECT count(*) FROM house_stage_crew_log)               AS crew_log_rows;

-- 5. The Огнян crew (missing from the new sheet): any history?
SELECT w.id, w.name, count(s.id) AS sessions
FROM worker w LEFT JOIN work_session s ON s.worker_id = w.id
WHERE w.id IN (455, 456, 457)
GROUP BY w.id, w.name;
