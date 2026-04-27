CREATE TABLE container (
    id               BIGSERIAL    PRIMARY KEY,
    container_number VARCHAR(50)  NOT NULL UNIQUE,
    status           VARCHAR(100),
    eta              TIMESTAMP,
    location         VARCHAR(255),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_container_number ON container(container_number);
