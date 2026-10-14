-- V26__bank_link_payments.sql
-- Estonian bank-link payments through Montonio, offered alongside card payments.
--
-- New tables rather than a wider payments table (V13). That table's shape is a
-- Stripe PaymentIntent; a bank-link payment is a different object with its own
-- lifecycle. Both still settle an order the same way: a result goes onto the one
-- payment-result queue, and the consumer appends order_events.

CREATE TABLE bank_link_payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    montonio_order_uuid TEXT NOT NULL,
    payment_url         TEXT NOT NULL,
    bank_code           TEXT,
    amount              NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    currency            VARCHAR(3) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A Montonio notification names Montonio's order, not ours; this is how it is
-- traced back without trusting anything the browser sends.
CREATE UNIQUE INDEX bank_link_payments_montonio_uuid_unique ON bank_link_payments (montonio_order_uuid);

-- One bank-link payment per order. A retried checkout reuses it, so a network
-- hiccup cannot send the customer to the bank to pay twice.
CREATE UNIQUE INDEX bank_link_payments_order_unique ON bank_link_payments (order_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Montonio retries a notification until it gets a 2xx, for up to 48 hours, and
-- the customer's return to the shop carries the same signed result. The first
-- sighting of each (Montonio order, status) pair wins the insert; every later
-- copy is acknowledged and ignored, so a result can never be applied twice.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE processed_bank_link_results (
    montonio_order_uuid TEXT NOT NULL,
    payment_status      TEXT NOT NULL,
    processed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (montonio_order_uuid, payment_status)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Refund attempts for bank-link orders.
--
-- Montonio refunds are asynchronous and can be rejected. A network retry must
-- never create a second refund, so each attempt carries an idempotency key that
-- stays the same until that attempt settles; only a settled, unsuccessful
-- attempt lets a new one start with a new key.
--
-- outcome and resolved_at are written once, when the attempt settles. This is
-- payment bookkeeping, not the order record: the order's history is still only
-- ever appended to order_events.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE bank_link_refunds (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id             UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    montonio_order_uuid  TEXT NOT NULL,
    idempotency_key      TEXT NOT NULL UNIQUE,
    amount               NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3) NOT NULL,
    montonio_refund_uuid TEXT,
    outcome              TEXT CHECK (outcome IN ('SUCCESSFUL', 'REJECTED', 'CANCELED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ
);

-- At most one open attempt per order. Two refund requests racing each other then
-- share one attempt and one idempotency key, instead of each opening its own and
-- sending Montonio two refunds.
CREATE UNIQUE INDEX bank_link_refunds_one_open_per_order ON bank_link_refunds (order_id) WHERE outcome IS NULL;
