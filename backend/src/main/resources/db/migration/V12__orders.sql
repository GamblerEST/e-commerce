-- V12__orders.sql
-- Orders are immutable financial records (DECISIONS.md §1). There is no
-- status column on `orders` and no UPDATE path for one anywhere in the
-- application — current status is always DERIVED by folding `order_events`,
-- the same way a bank statement is a balance derived from a transaction
-- log, never a single mutable number.

CREATE TABLE orders (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- NULL for guest checkout — deliberately SET NULL,
    -- not a hard FK-cascade-delete: an order must survive account
    -- anonymization (DECISIONS.md §5), which is exactly why it snapshots
    -- its own billing details below instead of joining to `users`.
    user_id               UUID REFERENCES users(id) ON DELETE SET NULL,

    -- Client-generated, one per checkout attempt. A network retry resends
    -- the SAME key; the unique index below is what actually makes retrying
    -- safe rather than double-charging/double-ordering. Enforced here even
    -- though Stripe itself arrives later, because double-ORDER-creation is
    -- already possible without it.
    idempotency_key       TEXT NOT NULL,

    -- Billing snapshot — DECISIONS.md §2/§5: orders must never read PII via
    -- a join to `users`, both so a historical invoice can't be corrupted by
    -- a later profile edit and so GDPR anonymization of the user row can
    -- never corrupt an invoice that must survive it for 7 years.
    billing_name          TEXT NOT NULL,
    billing_email         TEXT NOT NULL,
    billing_address_line1 TEXT NOT NULL,
    billing_address_line2 TEXT,
    billing_city          TEXT NOT NULL,
    billing_postal_code   TEXT NOT NULL,
    billing_country       VARCHAR(2) NOT NULL,

    currency              VARCHAR(3) NOT NULL,
    -- Net / VAT / gross, each computed ONCE from this order's own
    -- snapshotted lines at creation time and stored — not a violation of
    -- "never recompute from current data" (DECISIONS.md §2), since nothing
    -- here is ever re-derived from `products` again after this INSERT.
    subtotal              NUMERIC(12,2) NOT NULL CHECK (subtotal >= 0),
    vat_total              NUMERIC(12,2) NOT NULL CHECK (vat_total >= 0),
    total                  NUMERIC(12,2) NOT NULL CHECK (total >= 0),

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The actual idempotency guard — see idempotency_key's comment above.
CREATE UNIQUE INDEX orders_idempotency_key_unique ON orders (idempotency_key);
CREATE INDEX orders_user_idx ON orders (user_id, created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- order_lines — SNAPSHOT product name/price/VAT rate at purchase time
-- (DECISIONS.md §2). product_id is kept ONLY for "show me this product
-- again"; pricing a historical order NEVER joins to `products`.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE order_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id      UUID REFERENCES products(id) ON DELETE SET NULL,

    product_name    TEXT NOT NULL,
    -- Gross (VAT-inclusive) unit price — what the customer actually saw and
    -- paid per unit, matching cart/product-page display. vat_rate is the
    -- ISO-4217-adjacent snapshot that lets subtotal/vat_total be recovered
    -- (or re-derived for reporting) without ever consulting current config.
    unit_price      NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    vat_rate        NUMERIC(5,4) NOT NULL CHECK (vat_rate >= 0),
    quantity        INT NOT NULL CHECK (quantity > 0),
    currency        VARCHAR(3) NOT NULL,
    line_total      NUMERIC(12,2) NOT NULL CHECK (line_total >= 0),

    -- Which inventory hold (InventoryService) backs this line — lets
    -- payment success/failure convert or release the exact
    -- right reservation without re-deriving it from quantities.
    reservation_id  UUID REFERENCES reservations(id) ON DELETE SET NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX order_lines_order_idx ON order_lines (order_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- order_events — append-only. Current order status is ALWAYS derived by
-- folding these, never read from a mutable column (DECISIONS.md §1).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE order_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    event_type  TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX order_events_order_idx ON order_events (order_id, occurred_at);

-- Same defence as audit_log (V1__identity.sql/V2__least_privilege.sql): a
-- trigger stops bugs, a withheld PRIVILEGE stops an attacker who has SQL
-- execution as shop_app. V2's `ALTER DEFAULT PRIVILEGES` already grants
-- shop_app full SELECT/INSERT/UPDATE/DELETE on every table created from here
-- on — including this one, the instant it's created below — so the REVOKE
-- is not optional cleanup, it is the entire point.
CREATE OR REPLACE FUNCTION order_events_is_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_events is append-only; % is not permitted', TG_OP;
END;
$$;

CREATE TRIGGER order_events_no_update
    BEFORE UPDATE OR DELETE ON order_events
    FOR EACH ROW EXECUTE FUNCTION order_events_is_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON order_events FROM shop_app;
