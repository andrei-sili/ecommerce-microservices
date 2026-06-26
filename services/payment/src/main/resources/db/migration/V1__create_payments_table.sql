CREATE TABLE payments (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id             UUID         NOT NULL,
    user_id              BIGINT       NOT NULL,
    amount               NUMERIC(12,2) NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    gateway              VARCHAR(30)  NOT NULL,
    gateway_payment_id   VARCHAR(100),
    payment_method_token VARCHAR(100) NOT NULL,
    failure_reason       VARCHAR(100),
    idempotency_key      VARCHAR(200) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Each idempotency key is unique across all payments.
CREATE UNIQUE INDEX uix_payments_idempotency_key ON payments (idempotency_key);

-- At most one successful charge per order (DB-level guard against double-charge).
CREATE UNIQUE INDEX uix_payments_order_succeeded ON payments (order_id) WHERE status = 'SUCCEEDED';

-- Lookup by order and user for ownership checks.
CREATE INDEX ix_payments_order_id ON payments (order_id);
CREATE INDEX ix_payments_user_id ON payments (user_id);
