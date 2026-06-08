-- ---------------------------------------------------------------------------
-- Base seed for crews + foundational workers (ids 1-23).
--
-- These rows were originally created by hand in a developer DB and never
-- captured as a migration, so a fresh database had an empty `crew` table and
-- no leaders/managers. That made the member inserts below fail with
-- `worker_crew_id_fkey` (crew_id 1..8 not present in crew) and left the V16
-- coord updates (which target ids 3,5,7,9-23 and 24-53) with nothing to hit.
--
-- This block reconstructs that base state so V14 -> V17 produce a coherent
-- dataset. Names/houses are best-effort and may differ from the original
-- hand-entered data. Layout reverse-engineered from the comments in this file
-- plus V16__crew_worker_coords.sql and V17__fix_crew_members.sql:
--   leaders : 3 Alpha, 5 Beta, 7 Gamma, 9 Delta, 10 Echo, 11 Zeta, 12 Eta, 13 Theta
--   members : 14-16 Delta, 17-18 Echo, 19-21 Zeta, 22-23 Eta
--   managers: 1,2,4,6,8
-- ---------------------------------------------------------------------------

-- 1) Crews (manager_id set later to break the worker<->crew FK cycle;
--    house_id column does not exist until V15, so it is not referenced here).
INSERT INTO crew (id, name) VALUES
  (1, 'Alpha Crew'),
  (2, 'Beta Crew'),
  (3, 'Gamma Crew'),
  (4, 'Delta Crew'),
  (5, 'Echo Crew'),
  (6, 'Zeta Crew'),
  (7, 'Eta Crew'),
  (8, 'Theta Crew');

-- 2) Foundational workers with explicit ids 1-23.
--    Managers have no trade (matches V13's CREW_MANAGER rule).
INSERT INTO worker (id, name, role, trade, crew_id) VALUES
  -- Managers (ids 1,2,4,6,8) — not crew members themselves
  ( 1, 'Dimitar Petrov',     'CREW_MANAGER', NULL,          NULL),
  ( 2, 'Stoyan Ivanov',      'CREW_MANAGER', NULL,          NULL),
  ( 4, 'Petar Georgiev',     'CREW_MANAGER', NULL,          NULL),
  ( 6, 'Angel Dimitrov',     'CREW_MANAGER', NULL,          NULL),
  ( 8, 'Iliya Stoyanov',     'CREW_MANAGER', NULL,          NULL),
  -- Crew leaders (id 5 left unassigned on purpose — V17 assigns it to Beta)
  ( 3, 'Kaloyan Iliev',      'CREW_LEADER',  'Framing',     1),
  ( 5, 'Martin Georgiev',    'CREW_LEADER',  'Plumbing',    NULL),
  ( 7, 'Emil Stoyanov',      'CREW_LEADER',  'Roofing',     3),
  ( 9, 'Valentin Kolev',     'CREW_LEADER',  'Electricity', 4),
  (10, 'Daniel Marinov',     'CREW_LEADER',  'Finishing',   5),
  (11, 'Pavel Dimitrov',     'CREW_LEADER',  'Roofing',     6),
  (12, 'Yordan Petkov',      'CREW_LEADER',  'Framing',     7),
  (13, 'Simeon Vasilev',     'CREW_LEADER',  'Plumbing',    8),
  -- Original crew members (ids 14-23)
  (14, 'Aleksandar Iliev',   'CREW_MEMBER',  'Framing',     4),
  (15, 'Boris Kolev',        'CREW_MEMBER',  'Plumbing',    4),
  (16, 'Damyan Stoyanov',    'CREW_MEMBER',  'Roofing',     4),
  (17, 'Filip Georgiev',     'CREW_MEMBER',  'Electricity', 5),
  (18, 'Grigor Marinov',     'CREW_MEMBER',  'Finishing',   5),
  (19, 'Ivan Dimitrov',      'CREW_MEMBER',  'Roofing',     6),
  (20, 'Kiril Petrov',       'CREW_MEMBER',  'Plumbing',    6),
  (21, 'Lachezar Todorov',   'CREW_MEMBER',  'Framing',     6),
  (22, 'Nayden Hristov',     'CREW_MEMBER',  'Electricity', 7),
  (23, 'Ognyan Vasilev',     'CREW_MEMBER',  'Finishing',   7);

-- 3) Advance the worker identity sequence past the explicit ids so the
--    member inserts below auto-generate ids 24-53 (exactly what V16 targets).
SELECT setval(pg_get_serial_sequence('worker', 'id'), 23, true);

-- 4) Now that workers exist, point each crew at its manager.
UPDATE crew SET manager_id = 1 WHERE id = 1;
UPDATE crew SET manager_id = 2 WHERE id = 2;
UPDATE crew SET manager_id = 4 WHERE id = 3;
UPDATE crew SET manager_id = 6 WHERE id = 4;
UPDATE crew SET manager_id = 8 WHERE id = 5;
UPDATE crew SET manager_id = 1 WHERE id = 6;
UPDATE crew SET manager_id = 2 WHERE id = 7;
UPDATE crew SET manager_id = 4 WHERE id = 8;

-- 5) Advance the crew identity sequence past the explicit ids so app-created
--    crews don't collide with ids 1-8.
SELECT setval(pg_get_serial_sequence('crew', 'id'), 8, true);

-- ---------------------------------------------------------------------------
-- Add at least 5 CREW_MEMBER workers per crew (original V14 content).
-- These auto-generate ids 24-53 thanks to the setval above.
-- ---------------------------------------------------------------------------
-- Alpha Crew (id=1): 0 members → add 5
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Georgi Stoyanov',    'CREW_MEMBER', 'Roofing',     1),
  ('Ivaylo Petrov',      'CREW_MEMBER', 'Plumbing',    1),
  ('Nikolay Hristov',    'CREW_MEMBER', 'Framing',     1),
  ('Tihomir Angelov',    'CREW_MEMBER', 'Electricity', 1),
  ('Borislav Vasilev',   'CREW_MEMBER', 'Finishing',   1);

-- Beta Crew (id=2): 0 members → add 5
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Momchil Ivanov',     'CREW_MEMBER', 'Roofing',     2),
  ('Svetlin Marinov',    'CREW_MEMBER', 'Plumbing',    2),
  ('Krasimir Yordanov',  'CREW_MEMBER', 'Framing',     2),
  ('Dobromir Kolev',     'CREW_MEMBER', 'Electricity', 2),
  ('Vladislav Todorov',  'CREW_MEMBER', 'Finishing',   2);

-- Gamma Crew (id=3): 0 members → add 5
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Atanas Dimitrov',    'CREW_MEMBER', 'Roofing',     3),
  ('Bozhidar Nikolov',   'CREW_MEMBER', 'Plumbing',    3),
  ('Zhivko Georgiev',    'CREW_MEMBER', 'Framing',     3),
  ('Lyuben Stanchev',    'CREW_MEMBER', 'Electricity', 3),
  ('Hristo Popov',       'CREW_MEMBER', 'Finishing',   3);

-- Delta Crew (id=4): 3 members → add 2
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Stanislav Naydenov', 'CREW_MEMBER', 'Roofing',     4),
  ('Miroslav Zahariev',  'CREW_MEMBER', 'Plumbing',    4);

-- Echo Crew (id=5): 2 members → add 3
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Radoslav Iliev',     'CREW_MEMBER', 'Roofing',     5),
  ('Veselin Petkov',     'CREW_MEMBER', 'Framing',     5),
  ('Todor Hristov',      'CREW_MEMBER', 'Finishing',   5);

-- Zeta Crew (id=6): 3 members → add 2
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Asen Angelov',       'CREW_MEMBER', 'Roofing',     6),
  ('Plamen Marinov',     'CREW_MEMBER', 'Electricity', 6);

-- Eta Crew (id=7): 2 members → add 3
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Dimitar Kolev',      'CREW_MEMBER', 'Roofing',     7),
  ('Stefan Vasilev',     'CREW_MEMBER', 'Plumbing',    7),
  ('Georgi Todorov',     'CREW_MEMBER', 'Framing',     7);

-- Theta Crew (id=8): 0 members → add 5
INSERT INTO worker (name, role, trade, crew_id) VALUES
  ('Nikolay Yordanov',   'CREW_MEMBER', 'Roofing',     8),
  ('Ivan Stoyanov',      'CREW_MEMBER', 'Plumbing',    8),
  ('Martin Hristov',     'CREW_MEMBER', 'Framing',     8),
  ('Boyan Dimitrov',     'CREW_MEMBER', 'Electricity', 8),
  ('Teodor Georgiev',    'CREW_MEMBER', 'Finishing',   8);
