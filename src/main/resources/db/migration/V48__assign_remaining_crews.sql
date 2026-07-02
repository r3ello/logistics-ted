-- Distribute the 13 unassigned crews evenly across 5 managers (~2-3 each)
-- Георги Димитров (394): structural/foundation crews
UPDATE crew SET manager_id = 394 WHERE id IN (13, 25, 43);  -- Конструкция, Комин, ВиК

-- Петър Стоянов (395): interior finishing crews
UPDATE crew SET manager_id = 395 WHERE id IN (49, 55, 61);  -- Гипсокартон, Замазка, Мазилка

-- Мария Иванова (396): floor/tile crews
UPDATE crew SET manager_id = 396 WHERE id IN (67, 73, 85);  -- Плочки, Ламинат, Первази

-- Стефан Колев (397): exterior/roof crews
UPDATE crew SET manager_id = 397 WHERE id IN (19, 79);      -- Улуци, Мълниезащита

-- Елена Николова (398): doors/misc crews
UPDATE crew SET manager_id = 398 WHERE id IN (37, 31);      -- Врати поръчка, Ниво замазка
