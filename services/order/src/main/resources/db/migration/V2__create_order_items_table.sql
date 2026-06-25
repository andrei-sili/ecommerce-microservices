-- Immutable line snapshot taken at placement (authoritative prices from the reservation response).
CREATE TABLE order_items (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     UUID         NOT NULL REFERENCES orders (id),
    product_id   BIGINT       NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    unit_price   NUMERIC(12,2) NOT NULL,
    quantity     INT          NOT NULL,
    line_total   NUMERIC(12,2) NOT NULL
);

-- Foreign keys are not auto-indexed by PostgreSQL (database.md); index for the order detail join.
CREATE INDEX ix_order_items_order_id ON order_items (order_id);
