-- V9__cart.sql
-- Persistent (logged-in) shopping cart. Guest carts live
-- entirely in Redis, keyed by an opaque cookie id — there is deliberately no
-- guest_cart table here; a cart only earns a Postgres row once it belongs to
-- a real account.
--
-- Cart line items are NOT order lines: they carry only product_id + quantity
-- and are priced from the LIVE product row on every read. Snapshotting name/
-- price/VAT only happens at order creation (DECISIONS.md §2) — a cart is a
-- shopping list, not a financial record, so it must reflect today's price.

CREATE TABLE carts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER carts_touch_updated_at
    BEFORE UPDATE ON carts
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE cart_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id     UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    -- No FK to products deliberately cascading further than SET NULL would
    -- allow: if a product is hard-removed the line should just disappear
    -- from the cart (cascade), unlike an order line which must survive
    -- (DECISIONS.md §2 — orders keep product_id only for "show me this
    -- product again", never hard-linked to catalog lifecycle).
    product_id  UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity    INT NOT NULL CHECK (quantity > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (cart_id, product_id)
);

CREATE INDEX cart_items_cart_idx ON cart_items (cart_id);

CREATE TRIGGER cart_items_touch_updated_at
    BEFORE UPDATE ON cart_items
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
