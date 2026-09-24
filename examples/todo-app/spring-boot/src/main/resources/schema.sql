-- j2r（--framework spring）が ../rust/migrations/0001_schema.sql にコピーする。
-- 起動時に毎回流すので、何度流してもよいように書く。
CREATE TABLE IF NOT EXISTS todos (
    id          BIGSERIAL    PRIMARY KEY,
    title       VARCHAR(100) NOT NULL,
    description TEXT,
    done        BOOLEAN      NOT NULL DEFAULT FALSE,
    priority    VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    due_date    DATE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- priority を足す前に作った表のため
ALTER TABLE todos ADD COLUMN IF NOT EXISTS priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';

CREATE TABLE IF NOT EXISTS activities (
    id         BIGSERIAL    PRIMARY KEY,
    todo_id    BIGINT       REFERENCES todos (id) ON DELETE SET NULL,
    action     VARCHAR(20)  NOT NULL,
    title      VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
