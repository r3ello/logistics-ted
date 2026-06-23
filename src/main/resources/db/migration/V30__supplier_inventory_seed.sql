-- Seed stock for the tier-3 external suppliers (suppliers themselves come from V2). Without
-- this the supplier fallback never fires in practice. unit_price is the vendor's price (a small
-- markup over the catalog material.price); it's informational — the fallback picks by distance.
-- IDs are explicit and the identity sequence is advanced past the seeded max (V2 convention).

-- material ids (from V2): 1 Lumber (28.50), 2 Screws (0.15), 3 Boards (12.00), 4 Tiles (3.80)
INSERT INTO supplier_inventory (id, supplier_id, material_id, quantity, unit_price) VALUES
    -- 1 BulgarBuild Sofia
    ( 1, 1, 1,  800.00, 31.00), ( 2, 1, 2, 5000.00, 0.18), ( 3, 1, 3, 300.00, 13.50), ( 4, 1, 4, 2000.00, 4.20),
    -- 2 PlovdivMat EOOD
    ( 5, 2, 1,  600.00, 30.50), ( 6, 2, 2, 4000.00, 0.17), ( 7, 2, 3, 250.00, 13.00), ( 8, 2, 4, 1500.00, 4.10),
    -- 3 Varna Construction Co
    ( 9, 3, 1,  900.00, 32.00), (10, 3, 2, 6000.00, 0.19), (11, 3, 3, 350.00, 14.00), (12, 3, 4, 2500.00, 4.40),
    -- 4 Burgas Materiali
    (13, 4, 1,  500.00, 31.50), (14, 4, 2, 3500.00, 0.18), (15, 4, 3, 200.00, 13.80), (16, 4, 4, 1200.00, 4.30),
    -- 5 RuseBuild OOD
    (17, 5, 1,  700.00, 30.00), (18, 5, 2, 4500.00, 0.16), (19, 5, 3, 280.00, 12.80), (20, 5, 4, 1800.00, 4.00),
    -- 6 Zagora Supplies
    (21, 6, 1,  650.00, 31.20), (22, 6, 2, 4200.00, 0.18), (23, 6, 3, 260.00, 13.20), (24, 6, 4, 1600.00, 4.15);
SELECT setval(pg_get_serial_sequence('supplier_inventory', 'id'), (SELECT MAX(id) FROM supplier_inventory));
