-- V17__delivery_options.sql
-- Delivery methods an admin can manage, plus the SNAPSHOT of the chosen one
-- on the order.
--
-- The snapshot is the whole point, and it is the same rule as order lines
-- (DECISIONS.md §2): an order must never join back to delivery_options to
-- describe how it shipped. Rename "Omniva parcel machine" to something else,
-- reprice it, or retire it entirely, and every historical invoice still says
-- what the customer actually agreed to and paid.

CREATE TABLE delivery_options (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Stable machine key. The client picks by code, so renaming the
    -- customer-facing `name` never breaks an integration or a saved cart.
    code                TEXT NOT NULL,
    name                TEXT NOT NULL,
    carrier             TEXT,

    -- GROSS (VAT-inclusive), matching products.price — the whole codebase
    -- back-calculates net from gross rather than storing both, so a mix of
    -- conventions would silently produce wrong VAT on shipping.
    price               NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    currency            VARCHAR(3) NOT NULL DEFAULT 'EUR',

    -- Feeds the "estimated delivery" the order confirmation page must show.
    -- A range, not a single number: couriers quote ranges, and a fake-precise
    -- single date is a promise the shop cannot keep.
    lead_time_days_min  INT NOT NULL CHECK (lead_time_days_min >= 0),
    lead_time_days_max  INT NOT NULL CHECK (lead_time_days_max >= 0),
    CONSTRAINT delivery_lead_time_ordered CHECK (lead_time_days_max >= lead_time_days_min),

    -- Retired rather than deleted: an old order snapshots its delivery
    -- details, but keeping the row means admin screens can still resolve the
    -- code to something recognisable.
    active              BOOLEAN NOT NULL DEFAULT true,
    sort_order          INT NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX delivery_options_code_unique ON delivery_options (code);
CREATE INDEX delivery_options_active_idx ON delivery_options (active, sort_order);

CREATE TRIGGER delivery_options_touch_updated_at
    BEFORE UPDATE ON delivery_options
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ─────────────────────────────────────────────────────────────────────────────
-- The snapshot on the order. All nullable: orders placed before this existed
-- have no delivery method, and backfilling a guess onto a financial record
-- would be inventing history.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE orders
    ADD COLUMN delivery_code           TEXT,
    ADD COLUMN delivery_name           TEXT,
    ADD COLUMN delivery_price          NUMERIC(12,2) CHECK (delivery_price IS NULL OR delivery_price >= 0),
    ADD COLUMN delivery_lead_days_min  INT,
    ADD COLUMN delivery_lead_days_max  INT;

-- Two starter methods so a fresh checkout has something to choose. Prices are
-- gross. Deliberately seeded rather than left empty: an empty delivery table
-- makes checkout look broken on a clean install.
INSERT INTO delivery_options (code, name, carrier, price, currency, lead_time_days_min, lead_time_days_max, sort_order)
VALUES
    ('omniva_parcel', 'Omniva parcel machine', 'Omniva', 3.49, 'EUR', 1, 3, 10),
    ('courier_home',  'Courier to your door',  'Omniva', 5.99, 'EUR', 1, 2, 20);
