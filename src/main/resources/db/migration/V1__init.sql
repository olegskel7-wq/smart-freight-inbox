CREATE TABLE app_user (
    id        BIGSERIAL    PRIMARY KEY,
    email     VARCHAR(255) NOT NULL UNIQUE,
    password  VARCHAR(255) NOT NULL,
    role      VARCHAR(50)
);

CREATE TABLE container (
    id               BIGSERIAL    PRIMARY KEY,
    container_number VARCHAR(50)  NOT NULL UNIQUE,
    status           VARCHAR(100),
    eta              TIMESTAMP,
    location         VARCHAR(255)
);

CREATE TABLE email_ticket (
    id                         BIGSERIAL    PRIMARY KEY,
    message_id                 VARCHAR(255) NOT NULL UNIQUE,
    sender_email               VARCHAR(255),
    subject                    VARCHAR(500),
    body                       TEXT,
    extracted_container_number VARCHAR(50),
    status                     VARCHAR(50)  NOT NULL DEFAULT 'NEW',
    draft_reply                TEXT,
    retry_count                INTEGER      NOT NULL DEFAULT 0,
    deleted                    BOOLEAN      NOT NULL DEFAULT false,
    version                    INTEGER      NOT NULL DEFAULT 0,
    created_at                 TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_ticket_message_id ON email_ticket(message_id);
CREATE INDEX idx_email_ticket_status     ON email_ticket(status) WHERE deleted = false;
CREATE INDEX idx_container_number        ON container(container_number);
