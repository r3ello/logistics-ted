-- Worker check-in / check-out sessions.
-- session_date stores the calendar date for unique enforcement and fast filtering.
CREATE TABLE work_session (
    id               SERIAL PRIMARY KEY,
    worker_id        INTEGER      NOT NULL REFERENCES worker(id)  ON DELETE CASCADE,
    house_id         INTEGER      NOT NULL REFERENCES house(id)   ON DELETE CASCADE,
    session_date     DATE         NOT NULL DEFAULT CURRENT_DATE,
    checked_in_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    checked_out_at   TIMESTAMPTZ,
    device_id        VARCHAR(128) NOT NULL,
    check_in_lat     NUMERIC(9,6),
    check_in_lng     NUMERIC(9,6),
    check_out_lat    NUMERIC(9,6),
    check_out_lng    NUMERIC(9,6),
    -- One session per worker per house per day
    UNIQUE (worker_id, house_id, session_date)
);

CREATE INDEX idx_ws_worker ON work_session (worker_id, session_date DESC);
CREATE INDEX idx_ws_house  ON work_session (house_id,  session_date DESC);
CREATE INDEX idx_ws_device ON work_session (device_id, house_id, session_date);
