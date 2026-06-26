-- Append-only audit log. Never stores card data (PAN/CVV/secrets).
CREATE TABLE payment_transactions (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id       UUID         NOT NULL REFERENCES payments (id),
    type             VARCHAR(30)  NOT NULL,  -- AUTHORIZE / CAPTURE / WEBHOOK / STATUS_CHANGE
    status           VARCHAR(20)  NOT NULL,
    amount           NUMERIC(12,2),
    currency         VARCHAR(3),
    gateway_event_id VARCHAR(100),
    detail           VARCHAR(255), -- machine-safe description; never card data or secrets
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_payment_transactions_payment_id ON payment_transactions (payment_id);
