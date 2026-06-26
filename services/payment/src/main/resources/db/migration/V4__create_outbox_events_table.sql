-- Transactional outbox. Events are written here in the same tx as state changes; the relay
-- drains them to RabbitMQ. Same shape as User/Order outbox tables.
CREATE TABLE outbox_events (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Relay scans for unpublished events ordered by id (partial index keeps the scan efficient).
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (id) WHERE published_at IS NULL;
