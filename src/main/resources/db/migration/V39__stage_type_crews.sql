-- Link table: which crews are responsible for which stage type
CREATE TABLE stage_type_crew (
    id          SERIAL PRIMARY KEY,
    stage_order INTEGER NOT NULL,
    crew_id     INTEGER NOT NULL REFERENCES crew(id) ON DELETE CASCADE,
    UNIQUE (stage_order, crew_id)
);

-- Add crew assignment to house_stage
ALTER TABLE house_stage ADD COLUMN IF NOT EXISTS crew_id INTEGER REFERENCES crew(id) ON DELETE SET NULL;

-- Generate 78 crews (3 per each of 26 stages) with 1 leader + 3 members each
DO $$
DECLARE
  first_names text[] := ARRAY[
    'Иван','Георги','Петър','Николай','Димитър','Стефан','Антон','Мартин',
    'Виктор','Александър','Борис','Христо','Валери','Тодор','Радослав',
    'Владимир','Красимир','Стоян','Евгени','Любомир','Пламен','Калин',
    'Огнян','Бойко','Венцислав','Деян','Росен','Емил','Даниел','Андрей'
  ];
  last_names text[] := ARRAY[
    'Иванов','Георгиев','Петров','Николаев','Димитров','Стефанов','Антонов',
    'Мартинов','Виков','Александров','Борисов','Христов','Пенев','Тодоров',
    'Владимиров','Кирилов','Стоянов','Лазаров','Колев','Попов'
  ];
  stage_names text[] := ARRAY[
    'Конструкция','Ламперия','Улуци','Покривно покритие','Комин',
    'Дограма размери','Ниво замазка','Дограма монтаж','Врати поръчка','Ел',
    'ВиК','Изолация','Гипсокартон','Шпакловка','Замазка','Конзоли Ел',
    'Мазилка','Водостоци','Плочки','Боя','Ламинат','Ключове и контакти',
    'Мълниезащита','Врати','Первази','Приключване'
  ];
  s int; c int; w int;
  new_crew_id int;
  leader_id   int;
  idx int := 1;
  fn_len int := array_length(first_names, 1);
  ln_len int := array_length(last_names,  1);
BEGIN
  FOR s IN 1..26 LOOP
    FOR c IN 1..3 LOOP
      -- Create crew
      INSERT INTO crew(name)
      VALUES (stage_names[s] || ' - Екип ' || c)
      RETURNING id INTO new_crew_id;

      -- Create crew leader
      INSERT INTO worker(name, location, role, crew_id)
      VALUES (
        first_names[((idx - 1) % fn_len) + 1] || ' ' || last_names[((idx - 1) % ln_len) + 1],
        'Sofia', 'CREW_LEADER', new_crew_id
      ) RETURNING id INTO leader_id;
      idx := idx + 1;

      -- Create 3 members
      FOR w IN 1..3 LOOP
        INSERT INTO worker(name, location, role, crew_id)
        VALUES (
          first_names[((idx - 1) % fn_len) + 1] || ' ' || last_names[((idx - 1) % ln_len) + 1],
          'Sofia', 'CREW_MEMBER', new_crew_id
        );
        idx := idx + 1;
      END LOOP;

      -- Link crew to stage type
      INSERT INTO stage_type_crew(stage_order, crew_id) VALUES (s, new_crew_id);
    END LOOP;
  END LOOP;
END $$;
