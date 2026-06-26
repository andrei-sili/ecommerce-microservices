-- Add expires_at to stock_reservations (Wave 3 TTL + sweeper).
-- Existing rows (Wave 2) receive now() as default — those are stable rows and
-- the sweeper will evaluate only RESERVED rows, so released rows are unaffected.
ALTER TABLE stock_reservations
    ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Widen the status check to allow COMMITTED (payment confirmed the sale).
ALTER TABLE stock_reservations
    DROP CONSTRAINT chk_stock_reservations_status;
ALTER TABLE stock_reservations
    ADD CONSTRAINT chk_stock_reservations_status
        CHECK (status IN ('RESERVED', 'RELEASED', 'COMMITTED'));

-- Composite index for the sweeper query (status = 'RESERVED' AND expires_at < now()).
CREATE INDEX idx_stock_reservations_status_expires_at
    ON stock_reservations (status, expires_at);
