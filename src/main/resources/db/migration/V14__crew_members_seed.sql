-- Add at least 5 CREW_MEMBER workers per crew
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
