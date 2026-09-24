CREATE TABLE IF NOT EXISTS items (
    id       BIGSERIAL    PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    category VARCHAR(10)  NOT NULL,
    price    INTEGER      NOT NULL,
    note     TEXT
);

CREATE TABLE IF NOT EXISTS audit (
    id         BIGSERIAL   PRIMARY KEY,
    action     VARCHAR(20) NOT NULL,
    item_id    BIGINT,
    created_at TIMESTAMP   NOT NULL
);
