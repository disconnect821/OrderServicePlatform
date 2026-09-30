CREATE TABLE IF NOT EXISTS payment (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    result_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payment_order_id ON payment(order_id);
