-- Track how many warehouse (depot) stops an alternative used, mirroring supplier_stops_count.
-- Defaulted so existing rows backfill to 0 and the NOT NULL holds.

ALTER TABLE order_route_option
    ADD COLUMN warehouse_stops_count INTEGER NOT NULL DEFAULT 0;
