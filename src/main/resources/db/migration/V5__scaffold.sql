-- Add scaffold status to each house.
-- NONE     = no scaffold on site
-- AVAILABLE = scaffold on site, not currently in use (can be transported)
-- IN_USE   = scaffold currently in use (cannot be transported)

ALTER TABLE house
    ADD COLUMN scaffold_status VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (scaffold_status IN ('NONE', 'AVAILABLE', 'IN_USE'));
