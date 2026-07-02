-- Bind a material order to a specific house stage (the matrix cell), so crew leaders can raise
-- orders scoped to their assigned house + stage. Nullable: existing/admin house-only orders stay valid.
ALTER TABLE material_order ADD COLUMN IF NOT EXISTS house_stage_id INTEGER REFERENCES house_stage(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_material_order_house_stage ON material_order(house_stage_id);
