-- Electric box: one per house, identified by a secure token for client access
CREATE TABLE electric_box (
    id         SERIAL PRIMARY KEY,
    house_id   INTEGER NOT NULL REFERENCES house(id) ON DELETE CASCADE,
    main_amps  INTEGER NOT NULL DEFAULT 200,
    label      VARCHAR(150),
    token      VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (house_id)
);

-- Each breaker/circuit slot in the box
CREATE TABLE electric_circuit (
    id          SERIAL PRIMARY KEY,
    box_id      INTEGER NOT NULL REFERENCES electric_box(id) ON DELETE CASCADE,
    slot_index  INTEGER NOT NULL,        -- position in the panel (0-based)
    side        VARCHAR(5) NOT NULL DEFAULT 'LEFT' CHECK (side IN ('LEFT','RIGHT')),
    label       VARCHAR(100),
    amps        INTEGER,
    type        VARCHAR(10) NOT NULL DEFAULT 'SINGLE' CHECK (type IN ('SINGLE','DOUBLE','TANDEM','EMPTY')),
    status      VARCHAR(10) NOT NULL DEFAULT 'ON'     CHECK (status IN ('ON','OFF','TRIPPED')),
    UNIQUE (box_id, slot_index, side)
);
