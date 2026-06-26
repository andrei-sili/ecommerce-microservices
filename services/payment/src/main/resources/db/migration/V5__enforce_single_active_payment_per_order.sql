-- Double-charge guard at the DB level. A payment is "active" while it is PENDING (reserved,
-- gateway charge in flight) or SUCCEEDED. Allowing at most one active payment per order means two
-- concurrent requests with different idempotency keys collide on the reservation INSERT before
-- either calls the gateway -- so the gateway is charged at most once per order. A FAILED payment is
-- not active, so a legitimate retry after a decline is still allowed.
--
-- Subsumes the prior SUCCEEDED-only guard (uix_payments_order_succeeded): any order with a
-- SUCCEEDED payment also has at most one active payment.
DROP INDEX IF EXISTS uix_payments_order_succeeded;

CREATE UNIQUE INDEX uix_payments_order_active
    ON payments (order_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');
