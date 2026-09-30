CREATE TABLE IF NOT EXISTS idempotency_key (
    key             VARCHAR(255) PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    response_json   TEXT,
    created_at      TIMESTAMP DEFAULT now(),
    expires_at      TIMESTAMP DEFAULT (now() + INTERVAL '24 hours')
);

CREATE INDEX IF NOT EXISTS idx_idempotency_user ON idempotency_key(user_id);
