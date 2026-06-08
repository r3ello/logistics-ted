-- Beta Crew (id=2): assign worker 5 (Martin Georgiev, CREW_LEADER) who is currently unassigned
UPDATE worker SET crew_id = 2 WHERE id = 5;

-- Delta Crew (id=4) → Burgas: add 2 more members
INSERT INTO worker (name, role, trade, crew_id, lat, lng) VALUES
  ('Georgi Nenov',    'CREW_MEMBER', 'Finishing',   4, 42.504, 27.470),
  ('Kamen Stoyanov',  'CREW_MEMBER', 'Electricity', 4, 42.487, 27.483);

-- Zeta Crew (id=6) → Pleven: add 2 more members
INSERT INTO worker (name, role, trade, crew_id, lat, lng) VALUES
  ('Rosen Dimitrov',  'CREW_MEMBER', 'Finishing',   6, 43.410, 24.615),
  ('Hristo Naydenov', 'CREW_MEMBER', 'Roofing',     6, 43.425, 24.600);
