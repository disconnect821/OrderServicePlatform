ALTER TABLE payment ADD CONSTRAINT uk_payment_order UNIQUE (order_id);
