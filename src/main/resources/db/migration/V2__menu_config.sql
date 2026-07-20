CREATE TABLE menu_config (
    menu_key     VARCHAR(50)  PRIMARY KEY,
    section      VARCHAR(20)  NOT NULL,  -- 'nav' or 'dash'
    label_en     VARCHAR(100) NOT NULL,
    label_bg     VARCHAR(100) NOT NULL,
    icon         VARCHAR(10)  NOT NULL,
    visible      BOOLEAN      NOT NULL DEFAULT true,
    sort_order   INTEGER      NOT NULL DEFAULT 0
);

-- Nav items
INSERT INTO menu_config (menu_key, section, label_en, label_bg, icon, visible, sort_order) VALUES
('nav_stagematrix',       'nav', 'Project Stages',          'Етапи по проект',       '📊', true,  1),
('nav_houses',            'nav', 'Houses',                  'Къщи',                  '🏠', true,  2),
('nav_workers',           'nav', 'Workers',                 'Работници',             '👷', true,  3),
('nav_crews',             'nav', 'Crews',                   'Екипи',                 '👥', true,  4),
('nav_orders',            'nav', 'Orders',                  'Поръчки',               '📦', true,  5),
('nav_deliveries',        'nav', 'Deliveries',              'Доставки',              '🚚', true,  6),
('nav_warehouses',        'nav', 'Warehouses',              'Складове',              '🏬', true,  7),
('nav_suppliers',         'nav', 'Suppliers',               'Доставчици',            '🏭', true,  8),
('nav_scaffold',          'nav', 'Scaffold',                'Скеле',                 '🏗️', true,  9),
('nav_stages',            'nav', 'House Stages',            'Етапи на къща',         '🔢', true,  10),
('nav_docs',              'nav', 'Company Documentation',   'Фирмена документация',  '📂', true,  11),
('nav_companyfolders',    'nav', 'Company Folders Template','Шаблон папки',          '🗂️', true,  12),
('nav_housefoldertemplate','nav','House Folder Template',   'Шаблон папки на къща',  '🏠', true,  13),
('nav_attendance',        'nav', 'Check-in Log',            'Журнал присъствие',     '📋', true,  14),
('nav_qrcodes',           'nav', 'House QR Codes',          'QR кодове',             '🔲', true,  15),
('nav_workeraccess',      'nav', 'Worker Access',           'Достъп работници',      '🔑', true,  16),
('nav_travelpay',         'nav', 'Travel Allowance',        'Пътни',                 '💰', true,  17),
('nav_map',               'nav', 'Location Overview',       'Обзор локации',         '🌍', true,  18),
('nav_order',             'nav', 'Route Recommendation',    'Препоръка маршрут',     '🗺️', true,  19),
('nav_scaffold_route',    'nav', 'Scaffold Route',          'Маршрут скеле',         '🏗️', true,  20),
('nav_history',           'nav', 'Route History',           'История маршрути',      '📜', true,  21),
('nav_auditlog',          'nav', 'Audit Log',               'Одит журнал',           '🧾', true,  22),
('nav_crews_full',        'nav', 'Crew Manager',            'Мениджър екипи',        '🗂️', true,  23),
('nav_electric',          'nav', 'Electric Boxes',          'Ел. табла',             '⚡', true,  24),

-- Dashboard tabs
('dash_stagematrix',      'dash','Project Stages',          'Етапи по проект',       '📊', true,  1),
('dash_houses',           'dash','Houses',                  'Къщи',                  '🏠', true,  2),
('dash_workers',          'dash','Workers',                 'Работници',             '👷', true,  3),
('dash_crews',            'dash','Crews',                   'Екипи',                 '👥', true,  4),
('dash_orders',           'dash','Orders',                  'Поръчки',               '📦', true,  5),
('dash_deliveries',       'dash','Deliveries',              'Доставки',              '🚚', true,  6),
('dash_warehouses',       'dash','Warehouses',              'Складове',              '🏬', true,  7),
('dash_suppliers',        'dash','Suppliers',               'Доставчици',            '🏭', true,  8),
('dash_travelpay',        'dash','Travel Allowance',        'Пътни',                 '💰', true,  9),
('dash_docs',             'dash','Company Documentation',   'Фирмена документация',  '📁', true,  10);
