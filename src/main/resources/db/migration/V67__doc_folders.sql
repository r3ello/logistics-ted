CREATE TABLE doc_folder (
    id         SERIAL PRIMARY KEY,
    code       VARCHAR(30)  NOT NULL,
    label_en   VARCHAR(255) NOT NULL,
    label_bg   VARCHAR(255) NOT NULL,
    icon       VARCHAR(10)  NOT NULL DEFAULT '',
    color      VARCHAR(20)  NOT NULL DEFAULT '#4f8ef7',
    link_url   VARCHAR(1000),
    sort_order INTEGER      NOT NULL DEFAULT 0,
    parent_id  INTEGER REFERENCES doc_folder(id) ON DELETE CASCADE
);

-- Top-level departments
INSERT INTO doc_folder (code, label_en, label_bg, icon, color, sort_order) VALUES
('00', 'Control',                  'Контрол',              '🛡️', '#4f8ef7', 0),
('01', 'Sales',                    'Продажби',             '📈', '#3ecf6e', 1),
('02', 'Active Sites',             'Обекти в строеж',      '🏗️', '#f97316', 2),
('03', 'Finance',                  'Финанси',              '🧮', '#a78bfa', 3),
('04', 'Administration & HR',      'Администрация и ЧР',   '👥', '#fb7185', 4),
('05', 'Standards & Templates',    'Стандарти и Шаблони',  '📋', '#00c9b1', 5),
('06', 'Marketing',                'Маркетинг',            '📣', '#f59e0b', 6),
('07', 'Completed Sites',          'Обекти завършени',     '🏠', '#4f8ef7', 7),
('08', 'Purchases',                'Покупки',              '🛒', '#00c9b1', 8);

-- Subfolders for 00 - Control
INSERT INTO doc_folder (code, label_en, label_bg, icon, color, sort_order, parent_id)
SELECT code, label_en, label_bg, '📂', '#4f8ef7', sort_order, (SELECT id FROM doc_folder WHERE code='00')
FROM (VALUES
    ('doc00_01', '01_OBJECTS_CONTROL',   '01_КОНТРОЛ_ОБЕКТИ',      0),
    ('doc00_02', '02_EXPENSES_CONTROL',  '02_КОНТРОЛ_РАЗХОДИ',     1),
    ('doc00_03', '03_CLIENTS_CONTROL',   '03_КОНТРОЛ_КЛИЕНТИ',     2),
    ('doc00_04', '04_SUPPLIERS_CONTROL', '04_КОНТРОЛ_ДОСТАВЧИЦИ',  3),
    ('doc00_05', '05_CREWS_CONTROL',     '05_КОНТРОЛ_БРИГАДИ',     4),
    ('doc00_06', '06_FLEET_CONTROL',     '06_КОНТРОЛ_АВТОПАРК',    5),
    ('doc00_07', '07_MATERIALS_CONTROL', '07_КОНТРОЛ_МАТЕРИАЛИ',   6)
) AS t(code, label_en, label_bg, sort_order);

-- Subfolders for 01 - Sales
INSERT INTO doc_folder (code, label_en, label_bg, icon, color, sort_order, parent_id)
SELECT code, label_en, label_bg, '📂', '#3ecf6e', sort_order, (SELECT id FROM doc_folder WHERE code='01')
FROM (VALUES
    ('doc01_00a', '00_CRM',                    '00_CRM',                         0),
    ('doc01_00b', '00_NEW_CLIENTS',            '00_НОВИ_КЛИЕНТИ',                1),
    ('doc01_01',  '01_WITH_ARCHITECTS',        '01_ПРИ_АРХИТЕКТИ',               2),
    ('doc01_02',  '02_FOR_OFFER',              '02_ЗА_ОФЕРТА',                   3),
    ('doc01_03',  '03_OFFERS_FOR_APPROVAL',    '03_ОФЕРТИ_ЗА_ПОТВЪРЖДЕНИЕ',      4),
    ('doc01_04',  '04_SENT_OFFERS',            '04_ИЗПРАТЕНИ_ОФЕРТИ',            5),
    ('doc01_05',  '05_FOR_CONTRACT',           '05_ЗА_ДОГОВОР',                  6),
    ('doc01_06',  '06_CONTRACTS_FOR_APPROVAL', '06_ДОГОВОРИ_ЗА_ПОТВЪРЖДЕНИЕ',    7),
    ('doc01_07',  '07_SENT_CONTRACTS',         '07_ИЗПРАТЕНИ_ДОГОВОРИ',          8),
    ('doc01_08',  '08_CONTRACT_SIGNED',        '08_ДОГОВОР_ПОДПИСАН',            9),
    ('doc01_09',  '09_AUDIO',                  '09_АУДИО',                       10),
    ('doc01_10',  '10_CRM_ARCHIVE',            '10_CRM_АРХИВ',                   11)
) AS t(code, label_en, label_bg, sort_order);

-- Subfolders for 03 - Finance
INSERT INTO doc_folder (code, label_en, label_bg, icon, color, sort_order, parent_id)
SELECT code, label_en, label_bg, '📂', '#a78bfa', sort_order, (SELECT id FROM doc_folder WHERE code='03')
FROM (VALUES
    ('doc03_01', 'Accounting', 'Счетоводство', 0)
) AS t(code, label_en, label_bg, sort_order);

-- Subfolders for 04 - Administration & HR
INSERT INTO doc_folder (code, label_en, label_bg, icon, color, sort_order, parent_id)
SELECT code, label_en, label_bg, '📂', '#fb7185', sort_order, (SELECT id FROM doc_folder WHERE code='04')
FROM (VALUES
    ('doc04_01', '01_COMPANY_DOCUMENTS', '01_ФИРМЕНИ_ДОКУМЕНТИ', 0),
    ('doc04_02', '02_HR',                '02_HR',                 1),
    ('doc04_03', '03_CONTRACTS',         '03_ДОГОВОРИ',           2),
    ('doc04_07', '07_PERSONNEL',         '07_ПЕРСОНАЛ',           3)
) AS t(code, label_en, label_bg, sort_order);

-- Subfolders for 05 - Standards & Templates
INSERT INTO doc_folder (code, label_en, label_bg, icon, color, sort_order, parent_id)
SELECT code, label_en, label_bg, '📂', '#00c9b1', sort_order, (SELECT id FROM doc_folder WHERE code='05')
FROM (VALUES
    ('doc05_01', 'Technical standards',    'Технически стандарти',     0),
    ('doc05_02', 'TEMPLATE_CREW_MASTER',   'ШАБЛОН_БРИГАДА_МАСТЪР',    1),
    ('doc05_03', 'TEMPLATE_INVOICE_ISSUE', 'ШАБЛОН_ИЗДАВАНЕ_ФАКТУРИ',  2),
    ('doc05_04', 'TEMPLATE_MATERIALS',     'ШАБЛОН_МАТЕРИАЛИ',         3),
    ('doc05_05', 'TEMPLATE_REPORTS',       'ШАБЛОН_ОТЧЕТИ',            4),
    ('doc05_06', 'TEMPLATE_RECEIPT',       'ШАБЛОН_РАЗПИСКА',          5),
    ('doc05_07', 'TEMPLATE_CONTRACTS',     'ШАБЛОН_ДОГОВОРИ',          6),
    ('doc05_08', 'TEMPLATE_REQUEST',       'ШАБЛОН_ЗАЯВКА',            7),
    ('doc05_09', 'TEMPLATE_OFFERS',        'ШАБЛОН_ОФЕРТИ',            8),
    ('doc05_10', 'TEMPLATE_FOLDERS',       'ШАБЛОН_ПАПКИ',             9),
    ('doc05_11', 'TEMPLATE_COMPLETION',    'ШАБЛОН_ПРИКЛЮЧВАНЕ',       10)
) AS t(code, label_en, label_bg, sort_order);
