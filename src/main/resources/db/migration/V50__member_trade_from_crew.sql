-- Set trade on CREW_MEMBERs based on their crew's stage_order
UPDATE worker w
SET trade = CASE
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Ел%' LIMIT 1)         THEN 'Electricity'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Конзоли%' LIMIT 1)   THEN 'Electricity'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Мълниезащита%' LIMIT 1) THEN 'Electricity'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Ключове%' LIMIT 1)   THEN 'Electricity'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%ВиК%' LIMIT 1)       THEN 'Plumbing'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Покривно%' LIMIT 1)  THEN 'Roofing'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Улуци%' LIMIT 1)     THEN 'Roofing'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Водостоци%' LIMIT 1) THEN 'Roofing'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Комин%' LIMIT 1)     THEN 'Roofing'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Конструкция%' LIMIT 1) THEN 'Framing'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Дограма%' LIMIT 1)   THEN 'Framing'
    WHEN c.stage_order IN (SELECT stage_order FROM house_stage WHERE stage_name ILIKE '%Фундамент%' LIMIT 1) THEN 'Framing'
    ELSE 'Finishing'
END
FROM crew c
WHERE w.crew_id = c.id
  AND w.role = 'CREW_MEMBER'
  AND c.stage_order IS NOT NULL
  AND (w.trade IS NULL OR w.trade = '');
