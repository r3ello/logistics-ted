-- Normalize English trade values (from old tradeFromStageName() mapping) to Bulgarian stage names
UPDATE worker SET trade = 'Конструкция'      WHERE trade = 'Framing';
UPDATE worker SET trade = 'Ел'               WHERE trade = 'Electricity';
UPDATE worker SET trade = 'ВиК'              WHERE trade = 'Plumbing';
UPDATE worker SET trade = 'Покривно покритие' WHERE trade = 'Roofing';
UPDATE worker SET trade = 'Боя'              WHERE trade = 'Finishing';
