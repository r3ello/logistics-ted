CREATE TABLE menu_config (
    menu_key     VARCHAR(50)  PRIMARY KEY,
    section      VARCHAR(20)  NOT NULL,  -- 'nav' or 'dash'
    label_en     VARCHAR(100) NOT NULL,
    label_bg     VARCHAR(100) NOT NULL,
    icon         VARCHAR(10)  NOT NULL,
    visible      BOOLEAN      NOT NULL DEFAULT true,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    is_group     BOOLEAN      NOT NULL DEFAULT false,
    parent_key   VARCHAR(50)  REFERENCES menu_config(menu_key)
);

-- Nav groups (no parent), in sidebar order
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group) VALUES
('nav_group_management',     'nav', 'Management',     'Управление',      '⚙️', true, 1,  true),
('nav_group_docs',           'nav', 'Documentation',  'Документация',    '📁', true, 2,  true),
('nav_group_attendance',     'nav', 'Attendance',     'Присъствие',      '🕐', true, 3,  true),
('nav_group_routes',         'nav', 'Routes',         'Маршрути',        '🛣️', true, 4,  true),
('nav_group_orgchart',       'nav', 'Org Chart',      'Орг. структура',  '🏢', true, 5,  true),
('nav_group_clientservices', 'nav', 'Client Services','Клиентски услуги','🔌', true, 6,  true);

-- Management children
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key) VALUES
('nav_stagematrix',         'nav', 'Project Stages',          'Етапи по проект',       '📊', true,  1,  false, 'nav_group_management'),
('nav_houses',              'nav', 'Houses',                  'Къщи',                  '🏠', true,  2,  false, 'nav_group_management'),
('nav_workers',             'nav', 'Workers',                 'Работници',             '👷', true,  3,  false, 'nav_group_management'),
('nav_crews',               'nav', 'Crews',                   'Екипи',                 '👥', true,  4,  false, 'nav_group_management'),
('nav_orders',              'nav', 'Orders',                  'Поръчки',               '📦', true,  5,  false, 'nav_group_management'),
('nav_deliveries',          'nav', 'Deliveries',              'Доставки',              '🚚', true,  6,  false, 'nav_group_management'),
('nav_warehouses',          'nav', 'Warehouses',              'Складове',              '🏬', true,  7,  false, 'nav_group_management'),
('nav_suppliers',           'nav', 'Suppliers',               'Доставчици',            '🏭', true,  8,  false, 'nav_group_management'),
('nav_scaffold',            'nav', 'Scaffold',                'Скеле',                 '🏗️', true,  9,  false, 'nav_group_management'),
('nav_stages',              'nav', 'House Stages',            'Етапи на къща',         '🔢', true,  10, false, 'nav_group_management');

-- Documentation children
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key) VALUES
('nav_docs',                'nav', 'Company Documentation',   'Фирмена документация',  '📂', true,  1,  false, 'nav_group_docs'),
('nav_companyfolders',      'nav', 'Company Folders Template','Шаблон папки',          '🗂️', true,  2,  false, 'nav_group_docs'),
('nav_housefoldertemplate', 'nav', 'House Folder Template',   'Шаблон папки на къща',  '🏠', true,  3,  false, 'nav_group_docs');

-- Attendance children
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key) VALUES
('nav_attendance',          'nav', 'Check-in Log',            'Журнал присъствие',     '📋', true,  1,  false, 'nav_group_attendance'),
('nav_qrcodes',             'nav', 'House QR Codes',          'QR кодове',             '🔲', true,  2,  false, 'nav_group_attendance'),
('nav_workeraccess',        'nav', 'Worker Access',           'Достъп работници',      '🔑', true,  3,  false, 'nav_group_attendance');

-- Routes children
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key) VALUES
('nav_order',               'nav', 'Route Recommendation',    'Препоръка маршрут',     '🗺️', true,  1,  false, 'nav_group_routes'),
('nav_scaffold_route',      'nav', 'Scaffold Route',          'Маршрут скеле',         '🏗️', true,  2,  false, 'nav_group_routes'),
('nav_history',             'nav', 'Route History',           'История маршрути',      '📜', true,  3,  false, 'nav_group_routes'),
('nav_auditlog',            'nav', 'Audit Log',               'Одит журнал',           '🧾', true,  4,  false, 'nav_group_routes');

-- Org Chart children
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key) VALUES
('nav_crews_full',          'nav', 'Crew Manager',            'Мениджър екипи',        '🗂️', true,  1,  false, 'nav_group_orgchart');

-- Client Services children
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key) VALUES
('nav_electric',            'nav', 'Electric Boxes',          'Ел. табла',             '⚡', true,  1,  false, 'nav_group_clientservices');

-- Standalone nav items (no parent group)
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group) VALUES
('nav_travelpay',           'nav', 'Travel Allowance',        'Пътни',                 '💰', true,  7,  false),
('nav_map',                 'nav', 'Location Overview',       'Обзор локации',         '🌍', true,  8,  false);

-- Dashboard tabs (no groups)
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group) VALUES
('dash_stagematrix',       'dash', 'Project Stages',          'Етапи по проект',       '📊', true,  1,  false),
('dash_houses',            'dash', 'Houses',                  'Къщи',                  '🏠', true,  2,  false),
('dash_workers',           'dash', 'Workers',                 'Работници',             '👷', true,  3,  false),
('dash_crews',             'dash', 'Crews',                   'Екипи',                 '👥', true,  4,  false),
('dash_orders',            'dash', 'Orders',                  'Поръчки',               '📦', true,  5,  false),
('dash_deliveries',        'dash', 'Deliveries',              'Доставки',              '🚚', true,  6,  false),
('dash_warehouses',        'dash', 'Warehouses',              'Складове',              '🏬', true,  7,  false),
('dash_suppliers',         'dash', 'Suppliers',               'Доставчици',            '🏭', true,  8,  false),
('dash_travelpay',         'dash', 'Travel Allowance',        'Пътни',                 '💰', true,  9,  false),
('dash_docs',              'dash', 'Company Documentation',   'Фирмена документация',  '📁', true,  10, false);
