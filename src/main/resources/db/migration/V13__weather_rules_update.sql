-- Tighten weather rules based on real construction conditions
UPDATE stage_weather_rule SET max_precipitation_mm = 5.0                                    WHERE stage_order = 2;  -- Structure: concrete mixing affected above 5mm
UPDATE stage_weather_rule SET max_precipitation_mm = 3.0, requires_dry_days_before = 2      WHERE stage_order = 8;  -- Screed Level: needs dry ground, 2 dry days before
UPDATE stage_weather_rule SET max_precipitation_mm = 5.0, requires_dry_days_before = 1      WHERE stage_order = 15; -- Filler: humidity affects adhesion
UPDATE stage_weather_rule SET max_precipitation_mm = 5.0                                    WHERE stage_order = 16; -- Screed: floor must be dry
UPDATE stage_weather_rule SET max_precipitation_mm = 5.0, min_temp_c = 8.0                  WHERE stage_order = 20; -- Tiles: adhesive fails in humidity, needs warmth
UPDATE stage_weather_rule SET max_precipitation_mm = 1.0                                    WHERE stage_order = 21; -- Paint: essentially zero rain tolerance
UPDATE stage_weather_rule SET max_precipitation_mm = 30.0                                   WHERE stage_order = 22; -- Laminate: fully indoor
UPDATE stage_weather_rule SET max_precipitation_mm = 30.0                                   WHERE stage_order = 25; -- Doors: fully indoor install
UPDATE stage_weather_rule SET max_precipitation_mm = 30.0                                   WHERE stage_order = 26; -- Skirting Boards: fully indoor

-- Roofing stages: add max temperature limits (heat is dangerous + damages materials)
UPDATE stage_weather_rule SET max_temp_c = 35.0 WHERE stage_order = 3;  -- Roof Lining: bitumen/membrane softens above 35°C
UPDATE stage_weather_rule SET max_temp_c = 38.0 WHERE stage_order = 4;  -- Gutters: metal expands, sealants fail
UPDATE stage_weather_rule SET max_temp_c = 35.0 WHERE stage_order = 5;  -- Roof Cover: tiles crack/expand, dangerous conditions
UPDATE stage_weather_rule SET max_temp_c = 38.0 WHERE stage_order = 6;  -- Chimney: mortar dries too fast in heat
