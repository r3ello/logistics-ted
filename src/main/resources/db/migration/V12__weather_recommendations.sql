-- Menu config: Weather group and Recommendations item
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key)
VALUES
  ('nav_group_weather',           'nav', 'Weather',         'Времето',  '🌤️', true, 10, true,  NULL),
  ('nav_weather_recommendations', 'nav', 'Recommendations', 'Препоръки','📋', true,  1, false, 'nav_group_weather');

-- Stage weather rules table
CREATE TABLE stage_weather_rule (
    stage_order             INTEGER      NOT NULL PRIMARY KEY REFERENCES stage_type(stage_order) ON DELETE CASCADE,
    max_precipitation_mm    NUMERIC(5,1),
    min_temp_c              NUMERIC(4,1),
    max_temp_c              NUMERIC(4,1),
    max_wind_kph            NUMERIC(5,1),
    requires_dry_days_before INTEGER DEFAULT 0,
    notes_en                VARCHAR(255),
    notes_bg                VARCHAR(255)
);

-- Rules for all 27 stages
INSERT INTO stage_weather_rule VALUES
  ( 1,  5.0,  2.0, NULL, 50.0, 1, 'No rain, above freezing, moderate wind', 'Без дъжд, над нулата, умерен вятър'),
  ( 2,  8.0,  0.0, NULL, 60.0, 0, 'Light rain ok, no freezing', 'Лек дъжд е допустим, без замръзване'),
  ( 3,  2.0,  5.0, NULL, 55.0, 0, 'Must be dry, warm enough', 'Трябва да е сухо и достатъчно топло'),
  ( 4,  3.0,  3.0, NULL, 45.0, 0, 'Dry day, low wind for installation', 'Сух ден, слаб вятър за монтаж'),
  ( 5,  2.0,  5.0, NULL, 40.0, 1, 'Dry and calm — tiles need stable conditions', 'Сухо и тихо — керемидите изискват стабилни условия'),
  ( 6,  5.0,  2.0, NULL, 55.0, 0, 'No rain, above freezing', 'Без дъжд, над нулата'),
  ( 7, 15.0,  0.0, NULL, 70.0, 0, 'Mostly wind-sensitive measurement work', 'Чувствително към вятър измервателно работа'),
  ( 8, 10.0,  2.0, NULL, 60.0, 1, 'Screed needs dry ground and above freezing', 'Замазката изисква сухо и над нулата'),
  ( 9,  5.0,  2.0, NULL, 50.0, 0, 'No rain for joinery install, no freezing', 'Без дъжд при монтаж на дограма'),
  (10, 30.0, NULL, NULL, NULL, 0, 'Indoor order — weather mostly irrelevant', 'Вътрешна поръчка — времето е без значение'),
  (11, 30.0, NULL, NULL, NULL, 0, 'Indoor electrical — weather mostly irrelevant', 'Вътрешен ел. монтаж — времето е без значение'),
  (12, 30.0, NULL, NULL, NULL, 0, 'Indoor plumbing — weather mostly irrelevant', 'Вътрешна ВиК — времето е без значение'),
  (13,  5.0,  5.0, NULL, 45.0, 0, 'Insulation needs dry and mild conditions', 'Изолацията изисква сухо и меко време'),
  (14, 30.0, NULL, NULL, NULL, 0, 'Indoor drywall — weather mostly irrelevant', 'Вътрешен гипсокартон — времето е без значение'),
  (15, 20.0,  5.0, 35.0, NULL, 0, 'Filler needs mild temp, avoid high humidity', 'Шпакловката изисква умерена температура'),
  (16, 10.0,  5.0, NULL, 55.0, 1, 'Screed needs dry conditions and above 5°C', 'Замазката изисква сухо и над 5°C'),
  (17, 30.0, NULL, NULL, NULL, 0, 'Indoor brackets — weather mostly irrelevant', 'Вътрешни конзоли — времето е без значение'),
  (18,  5.0,  5.0, 35.0, 40.0, 1, 'Plaster needs dry, mild temp, low wind', 'Мазилката изисква сухо, умерена темп. и слаб вятър'),
  (19,  3.0,  2.0, NULL, 45.0, 0, 'Downpipes — dry and calm for outdoor work', 'Водостоците изискват сухо и тихо'),
  (20, 20.0,  5.0, NULL, NULL, 0, 'Tiles — no freeze, moderate humidity ok', 'Плочките изискват без замръзване'),
  (21, 10.0,  8.0, 35.0, 30.0, 1, 'Paint needs dry, mild temp, low wind/humidity', 'Боята изисква сухо, умерена темп., слаб вятър'),
  (22, 20.0,  5.0, NULL, NULL, 0, 'Laminate — no freeze, indoor humidity ok', 'Ламинатът — без замръзване'),
  (23, 30.0, NULL, NULL, NULL, 0, 'Indoor switches — weather mostly irrelevant', 'Вътрешни ключове — времето е без значение'),
  (24,  5.0,  2.0, NULL, 45.0, 0, 'Lightning protection — outdoor, dry and calm', 'Мълниезащита — на открито, сухо и тихо'),
  (25, 20.0,  5.0, NULL, NULL, 0, 'Door install — mild temp, light rain ok', 'Монтаж на врати — умерена темп.'),
  (26, 20.0,  5.0, NULL, NULL, 0, 'Skirting — indoor, mild temp', 'Първази — вътрешно, умерена темп.');
