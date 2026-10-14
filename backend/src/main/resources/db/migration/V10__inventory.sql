-- V10__inventory.sql
-- Real, checkout-safe inventory (DECISIONS.md §3) — on_hand / reserved, with
-- expiring reservations. Replaces products.stock_quantity as the source of
-- truth for "can this be sold"; that column (V3__catalog.sql) stays as a
-- display-only counter and is never read for oversell prevention.
--
-- Why two numbers instead of one: a plain `stock -= 1` is already atomic
-- (Postgres row-locks it), so a race is NOT the bug a single counter has.
-- The bug is DURATION — checkout isn't instant, the customer is off at
-- Stripe for up to a couple of minutes. Decrementing at payment confirmation
-- lets two people both pay for the last unit; decrementing at checkout start
-- locks stock behind carts that are never completed. `reserved` holds stock
-- for exactly the checkout window and expires automatically if payment never
-- lands (see `reservations` below and InventoryService's scheduled sweep).

CREATE TABLE inventory (
    product_id  UUID PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    on_hand     INT NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reserved    INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Belt-and-suspenders alongside InventoryService's atomic UPDATE guard:
    -- available stock (on_hand - reserved) can never go negative even if
    -- application code ever gets this wrong.
    CONSTRAINT inventory_reserved_not_exceed_on_hand CHECK (reserved <= on_hand)
);

CREATE TRIGGER inventory_touch_updated_at
    BEFORE UPDATE ON inventory
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Backfill: every already-seeded product gets a real inventory row, seeded
-- from the old display-only counter. From here on the two numbers are
-- independent — restocking updates `inventory.on_hand`, not `products.
-- stock_quantity` (a later admin-facing pass can decide whether to keep
-- displaying that column at all, or read availability from here instead).
INSERT INTO inventory (product_id, on_hand, reserved)
SELECT id, stock_quantity, 0 FROM products WHERE deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- reservations
--
-- released_at and sold_at are separate nullable columns, not one status enum
-- column: a reservation is "active" while BOTH are NULL, and the atomic claim
-- (InventoryService/ReservationRepository) races on whichever the caller is
-- attempting — `UPDATE ... WHERE released_at IS NULL AND sold_at IS NULL` —
-- so the scheduled expiry sweep and a real payment success can never both
-- win the same reservation.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE reservations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity    INT NOT NULL CHECK (quantity > 0),
    expires_at  TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    sold_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial index: only active reservations are ever scanned by the expiry
-- sweep, and there are far fewer of those than resolved ones over time.
CREATE INDEX reservations_active_expiry_idx ON reservations (expires_at)
    WHERE released_at IS NULL AND sold_at IS NULL;

CREATE INDEX reservations_product_idx ON reservations (product_id);
