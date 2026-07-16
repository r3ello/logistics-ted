-- ────────────────────────────────────────────────────────────
-- V3: Remove number/code prefixes from folder label names
-- ────────────────────────────────────────────────────────────

-- House folder templates
UPDATE public.doc_folder_template SET label_bg = 'Чертежи'                         WHERE label_en = 'Drawings';
UPDATE public.doc_folder_template SET label_bg = 'Договори и Приложения'            WHERE label_en = 'Contracts & Annexes';
UPDATE public.doc_folder_template SET label_bg = 'Снимки'                           WHERE label_en = 'Photos';
UPDATE public.doc_folder_template SET label_bg = 'Материали и Логистика'            WHERE label_en = 'Materials & Logistics';
UPDATE public.doc_folder_template SET label_bg = 'Комуникация с Клиента'            WHERE label_en = 'Client Communication';
UPDATE public.doc_folder_template SET label_bg = 'Работна документация и Протоколи' WHERE label_en = 'Working Docs & Protocols';
UPDATE public.doc_folder_template SET label_bg = 'Отчети труд'                      WHERE label_en = 'Labour Reports';
UPDATE public.doc_folder_template SET label_bg = 'Резултати'                        WHERE label_en = 'Results';
UPDATE public.doc_folder_template SET label_bg = 'Контрол качество'                 WHERE label_en = 'Quality Control';

-- Company folder subfolders — Control (parent code 00)
UPDATE public.doc_folder SET label_bg = 'Контрол обекти',     label_en = 'Objects Control'   WHERE code = 'doc00_01';
UPDATE public.doc_folder SET label_bg = 'Контрол разходи',    label_en = 'Expenses Control'  WHERE code = 'doc00_02';
UPDATE public.doc_folder SET label_bg = 'Контрол клиенти',    label_en = 'Clients Control'   WHERE code = 'doc00_03';
UPDATE public.doc_folder SET label_bg = 'Контрол доставчици', label_en = 'Suppliers Control' WHERE code = 'doc00_04';
UPDATE public.doc_folder SET label_bg = 'Контрол бригади',    label_en = 'Crews Control'     WHERE code = 'doc00_05';
UPDATE public.doc_folder SET label_bg = 'Контрол автопарк',   label_en = 'Fleet Control'     WHERE code = 'doc00_06';
UPDATE public.doc_folder SET label_bg = 'Контрол материали',  label_en = 'Materials Control' WHERE code = 'doc00_07';

-- Company folder subfolders — Sales (parent code 01)
UPDATE public.doc_folder SET label_bg = 'CRM',                        label_en = 'CRM'                      WHERE code = 'doc01_00a';
UPDATE public.doc_folder SET label_bg = 'Нови клиенти',               label_en = 'New Clients'              WHERE code = 'doc01_00b';
UPDATE public.doc_folder SET label_bg = 'При архитекти',              label_en = 'With Architects'          WHERE code = 'doc01_01';
UPDATE public.doc_folder SET label_bg = 'За оферта',                  label_en = 'For Offer'                WHERE code = 'doc01_02';
UPDATE public.doc_folder SET label_bg = 'Оферти за потвърждение',     label_en = 'Offers for Approval'      WHERE code = 'doc01_03';
UPDATE public.doc_folder SET label_bg = 'Изпратени оферти',           label_en = 'Sent Offers'              WHERE code = 'doc01_04';
UPDATE public.doc_folder SET label_bg = 'За договор',                 label_en = 'For Contract'             WHERE code = 'doc01_05';
UPDATE public.doc_folder SET label_bg = 'Договори за потвърждение',   label_en = 'Contracts for Approval'   WHERE code = 'doc01_06';
UPDATE public.doc_folder SET label_bg = 'Изпратени договори',         label_en = 'Sent Contracts'           WHERE code = 'doc01_07';
UPDATE public.doc_folder SET label_bg = 'Договор подписан',           label_en = 'Contract Signed'          WHERE code = 'doc01_08';
UPDATE public.doc_folder SET label_bg = 'Аудио',                      label_en = 'Audio'                    WHERE code = 'doc01_09';
UPDATE public.doc_folder SET label_bg = 'CRM архив',                  label_en = 'CRM Archive'              WHERE code = 'doc01_10';

-- Company folder subfolders — Finance (parent code 03)
UPDATE public.doc_folder SET label_bg = 'Счетоводство', label_en = 'Accounting' WHERE code = 'doc03_01';

-- Company folder subfolders — Admin (parent code 04)
UPDATE public.doc_folder SET label_bg = 'Фирмени документи', label_en = 'Company Documents' WHERE code = 'doc04_01';
UPDATE public.doc_folder SET label_bg = 'HR',                label_en = 'HR'                WHERE code = 'doc04_02';
UPDATE public.doc_folder SET label_bg = 'Договори',          label_en = 'Contracts'         WHERE code = 'doc04_03';
UPDATE public.doc_folder SET label_bg = 'Персонал',          label_en = 'Personnel'         WHERE code = 'doc04_07';

-- Company folder subfolders — Standards (parent code 05)
UPDATE public.doc_folder SET label_bg = 'Технически стандарти', label_en = 'Technical Standards'  WHERE code = 'doc05_01';
UPDATE public.doc_folder SET label_bg = 'Шаблон бригада мастър',label_en = 'Crew Master Template' WHERE code = 'doc05_02';
UPDATE public.doc_folder SET label_bg = 'Шаблон фактури',       label_en = 'Invoice Template'     WHERE code = 'doc05_03';
UPDATE public.doc_folder SET label_bg = 'Шаблон материали',     label_en = 'Materials Template'   WHERE code = 'doc05_04';
UPDATE public.doc_folder SET label_bg = 'Шаблон отчети',        label_en = 'Reports Template'     WHERE code = 'doc05_05';
UPDATE public.doc_folder SET label_bg = 'Шаблон разписка',      label_en = 'Receipt Template'     WHERE code = 'doc05_06';
UPDATE public.doc_folder SET label_bg = 'Шаблон договори',      label_en = 'Contracts Template'   WHERE code = 'doc05_07';
UPDATE public.doc_folder SET label_bg = 'Шаблон заявка',        label_en = 'Request Template'     WHERE code = 'doc05_08';
UPDATE public.doc_folder SET label_bg = 'Шаблон оферти',        label_en = 'Offers Template'      WHERE code = 'doc05_09';
UPDATE public.doc_folder SET label_bg = 'Шаблон папки',         label_en = 'Folders Template'     WHERE code = 'doc05_10';
UPDATE public.doc_folder SET label_bg = 'Шаблон приключване',   label_en = 'Completion Template'  WHERE code = 'doc05_11';
