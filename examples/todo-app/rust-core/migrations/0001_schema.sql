-- j2r（--framework spring）が ../rust-core/migrations/0001_schema.sql にコピーする。
CREATE TABLE IF NOT EXISTS todos (
    id          BIGSERIAL    PRIMARY KEY,
    title       VARCHAR(100) NOT NULL,
    description TEXT,
    done        BOOLEAN      NOT NULL DEFAULT FALSE,
    due_date    DATE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
