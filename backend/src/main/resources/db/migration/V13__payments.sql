-- V13__payments.sql
-- Links a Stripe PaymentIntent to an order. Insert-
-- only in practice, same reasoning as order_lines: once a PaymentIntent is
-- created for an order, nothing here should ever change — the outcome
-- (succeeded/failed) is recorded on the ORDER as an order_event, not by
-- mutating this row.

CREATE TABLE payments (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                 UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    stripe_payment_intent_id TEXT NOT NULL,
    amount                   NUMERIC(12,2) NOT NULL,
    currency                 VARCHAR(3) NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The lookup the webhook handler actually needs: Stripe's event tells us
-- WHICH PaymentIntent resolved, not which order — this is how we get back
-- to order_id without trusting anything client-supplied.
CREATE UNIQUE INDEX payments_stripe_pi_unique ON payments (stripe_payment_intent_id);
CREATE INDEX payments_order_idx ON payments (order_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- processed_stripe_events — Stripe's own docs guarantee AT-LEAST-once webhook
-- delivery, never exactly-once. The same payment_intent.succeeded event can
-- arrive twice (a retry after a slow 200, a network blip); without this, a
-- replayed event would try to append a second PAID order_event and convert
-- the same inventory reservation to sold twice. The primary key IS the
-- guard — same "atomic claim via unique constraint" idiom as
-- orders.idempotency_key (V12__orders.sql): insert, and if it fails, this
-- event was already handled, skip it.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE processed_stripe_events (
    event_id     TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
