DROP TABLE IF EXISTS idempotency_key;

CREATE TABLE idempotency_key (
    operation_type VARCHAR(32) NOT NULL,
    key             VARCHAR(255) NOT NULL,
    user_id         BIGINT NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    status          VARCHAR(32),
    response_json   TEXT,
    resource_id     BIGINT,
    created_at      TIMESTAMP DEFAULT now(),
    expires_at      TIMESTAMP DEFAULT (now() + INTERVAL '24 hours'),
    PRIMARY KEY (operation_type, key)
);

CREATE INDEX idx_idempotency_op_key ON idempotency_key (operation_type, key);
CREATE INDEX idx_idempotency_user ON idempotency_key (user_id);
