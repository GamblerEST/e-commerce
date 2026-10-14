-- V22__user_addresses.sql
-- Saved delivery addresses (the account page lets a customer
-- "manage addresses").
--
-- Until now the only thing resembling this was GET /orders/last-address, which
-- prefills checkout from whatever the customer typed last time. That is a
-- convenience, not an address book: it cannot hold a second address, cannot be
-- corrected, and disappears the moment someone orders to a different place.
--
-- These are NOT the addresses on orders. An order snapshots its own billing
-- address and must never join to anything for it (DECISIONS.md §2) — editing a
-- saved address here must not, and cannot, rewrite what an old invoice says.
CREATE TABLE user_addresses (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- ON DELETE CASCADE is safe here, unlike on orders: an address book entry
    -- is not an accounting record. In practice the row goes when
    -- anonymize_user() runs, which is what erasure should do to it.
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- What the customer calls it ("Home", "Office"). Optional.
    label         TEXT CHECK (label IS NULL OR length(label) <= 60),

    -- Personal data, stored as AES-256-GCM ciphertext by the application
    -- (EncryptedStringConverter, V20). TEXT because ciphertext is far longer
    -- than the plaintext it replaces — this is why orders.billing_country had
    -- to be widened from VARCHAR(2).
    recipient_name TEXT NOT NULL,
    phone          TEXT,
    line1          TEXT NOT NULL,
    line2          TEXT,
    city           TEXT NOT NULL,
    postal_code    TEXT NOT NULL,
    country        TEXT NOT NULL,

    is_default    BOOLEAN NOT NULL DEFAULT false,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX user_addresses_user_idx ON user_addresses (user_id, created_at DESC);

-- At most one default per customer, enforced by the database rather than by
-- application code remembering to clear the old one. A partial unique index is
-- the right tool: it constrains only the rows where is_default is true, so any
-- number of non-default addresses coexist.
CREATE UNIQUE INDEX user_addresses_one_default_per_user
    ON user_addresses (user_id) WHERE is_default;

CREATE TRIGGER user_addresses_touch_updated_at
    BEFORE UPDATE ON user_addresses
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
