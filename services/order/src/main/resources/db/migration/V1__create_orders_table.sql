-- Orders are publicly identified by UUID (api-design.md). One row per placed order.
CREATE TABLE orders (
    id              UUID         PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    currency        CHAR(3)      NOT NULL,
    subtotal        NUMERIC(12,2) NOT NULL,
    total           NUMERIC(12,2) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A user lists only their own orders; index the ownership filter.
CREATE INDEX ix_orders_user_id ON orders (user_id);

-- Idempotency-Key replay returns the original order: enforce one order per key.
CREATE UNIQUE INDEX ux_orders_idempotency_key ON orders (idempotency_key);
