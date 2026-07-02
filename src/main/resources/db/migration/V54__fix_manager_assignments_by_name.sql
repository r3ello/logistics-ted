-- Re-apply manager name fixes and crew assignments using name-based lookups
-- (V46 and V48 used hardcoded IDs that only match the local dev DB sequence)

-- Rename the 5 managers by matching on their generated placeholder names
-- They are CREW_LEADER workers whose crew names contain the stage keywords

-- Assign manager_id on crew using crew name matching instead of hardcoded IDs
-- Manager workers are identified as leaders of specific crews by stage keyword

DO $$
DECLARE
  v_mgr_id integer;
BEGIN
  -- Георги Димитров → manages Конструкция, Комин, ВиК crews
  SELECT w.id INTO v_mgr_id
  FROM worker w JOIN crew c ON w.crew_id = c.id
  WHERE w.role = 'CREW_LEADER' AND c.name ILIKE '%Конструкция%' AND c.name ILIKE '%Екип 1%'
  LIMIT 1;
  IF v_mgr_id IS NOT NULL THEN
    UPDATE worker SET name = 'Георги Димитров' WHERE id = v_mgr_id;
    UPDATE crew SET manager_id = v_mgr_id
    WHERE name ILIKE '%Конструкция%' OR name ILIKE '%Комин%' OR name ILIKE '%ВиК%';
  END IF;

  -- Петър Стоянов → manages Гипсокартон, Замазка, Мазилка crews
  SELECT w.id INTO v_mgr_id
  FROM worker w JOIN crew c ON w.crew_id = c.id
  WHERE w.role = 'CREW_LEADER' AND c.name ILIKE '%Гипсокартон%' AND c.name ILIKE '%Екип 1%'
  LIMIT 1;
  IF v_mgr_id IS NOT NULL THEN
    UPDATE worker SET name = 'Петър Стоянов' WHERE id = v_mgr_id;
    UPDATE crew SET manager_id = v_mgr_id
    WHERE name ILIKE '%Гипсокартон%' OR name ILIKE '%Замазка%' OR name ILIKE '%Мазилка%';
  END IF;

  -- Мария Иванова → manages Плочки, Ламинат, Первази crews
  SELECT w.id INTO v_mgr_id
  FROM worker w JOIN crew c ON w.crew_id = c.id
  WHERE w.role = 'CREW_LEADER' AND c.name ILIKE '%Плочки%' AND c.name ILIKE '%Екип 1%'
  LIMIT 1;
  IF v_mgr_id IS NOT NULL THEN
    UPDATE worker SET name = 'Мария Иванова' WHERE id = v_mgr_id;
    UPDATE crew SET manager_id = v_mgr_id
    WHERE name ILIKE '%Плочки%' OR name ILIKE '%Ламинат%' OR name ILIKE '%Первази%';
  END IF;

  -- Стефан Колев → manages Улуци, Мълниезащита crews
  SELECT w.id INTO v_mgr_id
  FROM worker w JOIN crew c ON w.crew_id = c.id
  WHERE w.role = 'CREW_LEADER' AND c.name ILIKE '%Улуци%' AND c.name ILIKE '%Екип 1%'
  LIMIT 1;
  IF v_mgr_id IS NOT NULL THEN
    UPDATE worker SET name = 'Стефан Колев' WHERE id = v_mgr_id;
    UPDATE crew SET manager_id = v_mgr_id
    WHERE name ILIKE '%Улуци%' OR name ILIKE '%Мълниезащита%';
  END IF;

  -- Елена Николова → manages Врати поръчка, Ниво замазка crews
  SELECT w.id INTO v_mgr_id
  FROM worker w JOIN crew c ON w.crew_id = c.id
  WHERE w.role = 'CREW_LEADER' AND c.name ILIKE '%Врати поръчка%' AND c.name ILIKE '%Екип 1%'
  LIMIT 1;
  IF v_mgr_id IS NOT NULL THEN
    UPDATE worker SET name = 'Елена Николова' WHERE id = v_mgr_id;
    UPDATE crew SET manager_id = v_mgr_id
    WHERE name ILIKE '%Врати поръчка%' OR name ILIKE '%Ниво замазка%';
  END IF;
END $$;
