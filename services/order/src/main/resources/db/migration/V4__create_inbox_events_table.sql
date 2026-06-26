-- Inbox dedup table for idempotent payment event consumption (Wave 3).
-- dedup_key = paymentId (from event payload); event_type distinguishes completed/failed/cancelled.
-- Insert-on-conflict-do-nothing: if row exists, event was already processed.
CREATE TABLE inbox_events (
    dedup_key    VARCHAR(200) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (dedup_key, event_type)
);
