CREATE TABLE IF NOT EXISTS books (
    id       BIGSERIAL    PRIMARY KEY,
    title    VARCHAR(200) NOT NULL,
    author   VARCHAR(200),
    stock    INTEGER      NOT NULL DEFAULT 0,
    archived BOOLEAN      NOT NULL DEFAULT FALSE
);
