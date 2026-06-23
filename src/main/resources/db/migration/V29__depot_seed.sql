-- Seed a couple of company warehouses (depots) with stock so the tier-2 fallback has data
-- to work with out of the box. IDs are explicit and the identity sequences are advanced past
-- the seeded max afterwards, matching the V2 seed convention.

INSERT INTO depot (id, name, location, lat, lng) VALUES
    (1, 'Central Depot Sofia',   'Sofia, Bulgaria',   42.697700, 23.321900),
    (2, 'Central Depot Plovdiv', 'Plovdiv, Bulgaria', 42.150000, 24.750000);
SELECT setval(pg_get_serial_sequence('depot', 'id'), (SELECT MAX(id) FROM depot));

-- material ids (from V2): 1 Lumber, 2 Screws, 3 Boards, 4 Tiles
INSERT INTO depot_inventory (id, depot_id, material_id, quantity) VALUES
    (1, 1, 1,  500.00), (2, 1, 2, 2000.00), (3, 1, 3, 150.00), (4, 1, 4, 1000.00),
    (5, 2, 1,  300.00), (6, 2, 2, 1500.00), (7, 2, 3, 100.00), (8, 2, 4,  600.00);
SELECT setval(pg_get_serial_sequence('depot_inventory', 'id'), (SELECT MAX(id) FROM depot_inventory));
