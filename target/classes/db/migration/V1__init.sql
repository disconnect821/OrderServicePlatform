CREATE TABLE product (
    id                  BIGSERIAL PRIMARY KEY,
    sku                 VARCHAR(64)     NOT NULL UNIQUE,
    name                VARCHAR(255)    NOT NULL,
    price               NUMERIC(12, 2)  NOT NULL,
    available_quantity  INT             NOT NULL CHECK (available_quantity >= 0)
);

CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT          NOT NULL,
    status       VARCHAR(32)     NOT NULL,
    total_amount NUMERIC(12, 2)  NOT NULL,
    created_at   TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE TABLE order_item (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  BIGINT          NOT NULL REFERENCES product(id),
    quantity    INT             NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12, 2)  NOT NULL
);

CREATE INDEX idx_order_item_order_id ON order_item(order_id);
