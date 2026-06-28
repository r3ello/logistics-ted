CREATE TABLE house_stage (
    id           SERIAL PRIMARY KEY,
    house_id     INTEGER NOT NULL REFERENCES house(id) ON DELETE CASCADE,
    stage_order  INTEGER NOT NULL,
    stage_name   VARCHAR(120) NOT NULL,
    worker_name  VARCHAR(120),
    status       VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    notes        TEXT,
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (house_id, stage_order)
);

INSERT INTO house_stage (house_id, stage_order, stage_name)
SELECT h.id, s.stage_order, s.stage_name
FROM house h
CROSS JOIN (VALUES
    (1,  'Конструкция'),
    (2,  'Ламперия'),
    (3,  'Улуци'),
    (4,  'Покривно покритие'),
    (5,  'Комин'),
    (6,  'Дограма размери'),
    (7,  'Ниво замазка'),
    (8,  'Дограма монтаж'),
    (9,  'Врати поръчка'),
    (10, 'Ел'),
    (11, 'ВиК'),
    (12, 'Изолация'),
    (13, 'Гипсокартон'),
    (14, 'Шпакловка'),
    (15, 'Замазка'),
    (16, 'Конзоли Ел'),
    (17, 'Мазилка'),
    (18, 'Водостоци'),
    (19, 'Плочки'),
    (20, 'Боя'),
    (21, 'Ламинат'),
    (22, 'Ключове и контакти'),
    (23, 'Мълниезащита'),
    (24, 'Врати'),
    (25, 'Первази'),
    (26, 'Приключване')
) AS s(stage_order, stage_name);
