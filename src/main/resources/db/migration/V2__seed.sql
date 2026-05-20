-- Seed data ported from the original MySQL dump (database.sql).
-- IDs are inserted explicitly so the existing inventory FKs resolve. Identity sequences
-- are then advanced past the seeded max so future inserts don't collide.

INSERT INTO material (id, name, unit, price) VALUES
    (1, 'Lumber',  'm²',         28.50),
    (2, 'Screws',  'unidades',    0.15),
    (3, 'Boards',  'unidades',   12.00),
    (4, 'Tiles',   'unidades',    3.80);
SELECT setval(pg_get_serial_sequence('material', 'id'), (SELECT MAX(id) FROM material));

INSERT INTO house (id, name, location, lat, lng, start_date, current_phase) VALUES
    (1,  'House Sofia Centro',  'Sofia, Bulgaria',         42.697700, 23.321900, '2026-01-10', 'Framing'),
    (2,  'House Plovdiv',       'Plovdiv, Bulgaria',       42.150000, 24.750000, '2026-05-05', 'Electrical'),
    (3,  'House Varna Mar',     'Varna, Bulgaria',         43.214100, 27.914700, '2026-02-14', 'Roofing'),
    (4,  'House Burgas',        'Burgas, Bulgaria',        42.495800, 27.472600, '2026-03-03', 'Plumbing'),
    (6,  'House Stara Zagora',  'Stara Zagora, Bulgaria',  42.425800, 25.634500, '2026-04-28', 'Foundation'),
    (7,  'House Pleven',        'Pleven, Bulgaria',        43.416800, 24.606900, '2026-01-20', 'Drywall'),
    (8,  'House Sliven',        'Sliven, Bulgaria',        42.683300, 26.316700, '2026-03-17', 'Insulation'),
    (9,  'House Dobrich',       'Dobrich, Bulgaria',       43.566700, 27.833300, '2026-02-28', 'Framing'),
    (10, 'House Shumen',        'Shumen, Bulgaria',        43.270600, 26.922100, '2026-04-05', 'Roofing'),
    (17, 'House test',          'Sofia',                   42.558897, 23.392296, '2026-05-01', 'Foundation');
SELECT setval(pg_get_serial_sequence('house', 'id'), (SELECT MAX(id) FROM house));

INSERT INTO warehouse (id, house_id) VALUES
    (1, 1), (2, 2), (3, 3), (4, 4), (6, 6), (7, 7), (8, 8), (9, 9), (10, 10), (17, 17);
SELECT setval(pg_get_serial_sequence('warehouse', 'id'), (SELECT MAX(id) FROM warehouse));

INSERT INTO inventory (id, warehouse_id, material_id, quantity) VALUES
    (1, 1, 1, 120.00),  (2, 1, 2, 500.00),  (3, 1, 3, 30.00),  (4, 1, 4, 200.00),
    (5, 2, 1,   0.00),  (6, 2, 2, 320.00),  (7, 2, 3, 15.00),  (8, 2, 4, 150.00),
    (9, 3, 1, 200.00), (10, 3, 2, 800.00), (11, 3, 3, 50.00), (12, 3, 4, 400.00),
   (13, 4, 1,  60.00), (14, 4, 2, 150.00), (15, 4, 3, 10.00), (16, 4, 4,  80.00),
   (21, 6, 1,  45.00), (22, 6, 2, 200.00), (23, 6, 3, 25.00), (24, 6, 4, 100.00),
   (25, 7, 1, 175.00), (26, 7, 2, 450.00), (27, 7, 3, 40.00), (28, 7, 4, 320.00),
   (29, 8, 1,  90.00), (30, 8, 2, 370.00), (31, 8, 3, 20.00), (32, 8, 4, 130.00),
   (33, 9, 1, 250.00), (34, 9, 2, 700.00), (35, 9, 3, 60.00), (36, 9, 4, 290.00),
   (37, 10, 1, 130.00),(38, 10, 2, 480.00),(39, 10, 3, 35.00),(40, 10, 4, 210.00),
   (41, 17, 1,   0.00),(42, 17, 2,   0.00),(43, 17, 3,  0.00),(44, 17, 4,   0.00);
SELECT setval(pg_get_serial_sequence('inventory', 'id'), (SELECT MAX(id) FROM inventory));

INSERT INTO supplier (id, name, location, lat, lng) VALUES
    (1, 'BulgarBuild Sofia',     'Sofia, Bulgaria',        42.697700, 23.321900),
    (2, 'PlovdivMat EOOD',       'Plovdiv, Bulgaria',      42.150000, 24.750000),
    (3, 'Varna Construction Co', 'Varna, Bulgaria',        43.214100, 27.914700),
    (4, 'Burgas Materiali',      'Burgas, Bulgaria',       42.495800, 27.472600),
    (5, 'RuseBuild OOD',         'Ruse, Bulgaria',         43.846800, 25.954400),
    (6, 'Zagora Supplies',       'Stara Zagora, Bulgaria', 42.425800, 25.634500);
SELECT setval(pg_get_serial_sequence('supplier', 'id'), (SELECT MAX(id) FROM supplier));

-- Bcrypt hashes ($2b$10$...) are kept verbatim; Spring's BCryptPasswordEncoder matches them.
-- admin / admin    and    user / user
INSERT INTO app_user (id, username, password_hash, role, created_at) VALUES
    (1, 'admin', '$2b$10$t01RgPA7kYT3F0ML0zyNhuNAQIkq2m3p6gGwV.JtIJANEaXf0xBSS', 'admin', '2026-05-17 20:50:57'),
    (2, 'user',  '$2b$10$E1JpDkL3aFadFdQVsEc80./Jc/ifz6mhD.HbZYhOZtidx3EpMnS0m', 'user',  '2026-05-17 20:50:57');
SELECT setval(pg_get_serial_sequence('app_user', 'id'), (SELECT MAX(id) FROM app_user));
