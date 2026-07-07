CREATE TABLE doc_document (
    id          SERIAL PRIMARY KEY,
    folder_id   INTEGER      NOT NULL REFERENCES doc_folder(id) ON DELETE CASCADE,
    title_en    VARCHAR(255) NOT NULL,
    title_bg    VARCHAR(255) NOT NULL DEFAULT '',
    link_url    VARCHAR(1000),
    doc_type    VARCHAR(20)  NOT NULL DEFAULT 'PDF',
    description VARCHAR(500),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
