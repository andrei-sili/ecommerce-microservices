-- Idempotency dedup for gateway webhook events (keyed by gateway event id).
CREATE TABLE processed_webhook_events (
    gateway_event_id VARCHAR(100) PRIMARY KEY,
    payment_id       UUID         NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
