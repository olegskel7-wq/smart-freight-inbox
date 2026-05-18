CREATE TABLE ticket_audit_log (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT       NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    details     TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ticket_audit_log_ticket_id ON ticket_audit_log (ticket_id);
CREATE INDEX idx_ticket_audit_log_created_at ON ticket_audit_log (created_at DESC);
