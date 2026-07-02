-- Derive trade for crew leaders from their crew's name
UPDATE worker w
SET trade = CASE
    WHEN c.name ILIKE '%Ел%'              THEN 'Electricity'
    WHEN c.name ILIKE '%Конзоли%'         THEN 'Electricity'
    WHEN c.name ILIKE '%Мълниезащита%'    THEN 'Electricity'
    WHEN c.name ILIKE '%Ключове%'         THEN 'Electricity'
    WHEN c.name ILIKE '%ВиК%'             THEN 'Plumbing'
    WHEN c.name ILIKE '%Покривно%'        THEN 'Roofing'
    WHEN c.name ILIKE '%Улуци%'           THEN 'Roofing'
    WHEN c.name ILIKE '%Водостоци%'       THEN 'Roofing'
    WHEN c.name ILIKE '%Комин%'           THEN 'Roofing'
    WHEN c.name ILIKE '%Конструкция%'     THEN 'Framing'
    WHEN c.name ILIKE '%Дограма%'         THEN 'Framing'
    WHEN c.name ILIKE '%Фундамент%'       THEN 'Framing'
    ELSE 'Finishing'
END
FROM crew c
WHERE w.crew_id = c.id
  AND w.role = 'CREW_LEADER'
  AND (w.trade IS NULL OR w.trade = '');
