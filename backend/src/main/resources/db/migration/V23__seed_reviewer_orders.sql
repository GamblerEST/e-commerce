-- V23__seed_reviewer_orders.sql
-- Makes the seeded reviews consistent with the rest of the seeded data.
--
-- The inconsistency this closes
-- ─────────────────────────────
-- V19 seeded 218 reviews from 20 demo accounts, and deliberately inserted them
-- directly — bypassing ReviewService's rule that only a verified purchaser may
-- review. The rule genuinely still holds for anything submitted through the
-- API, and that is where it matters. But it left the seed self-contradicting:
-- twenty people had written reviews and bought nothing. Anyone cross-checking
-- reviewers against orders finds reviews from customers with no purchase
-- history, which undermines the very rule the shop advertises.
--
-- V19's stated reason for not doing this was that seeding matching orders
-- "would add a large amount of fake financial history". That is true, and it is
-- the right call for a production database — but this is demo seed data whose
-- entire purpose is to make the shop demonstrable, and an inconsistent seed is
-- a worse lie than a visible one. It also gives the admin order screens,
-- order-status filtering and the refund workflow something real to show.
--
-- One PAID order per reviewer, containing exactly the products that reviewer
-- reviewed. So every seeded review now traces to a real purchase, and
-- ReviewService.canReview() would return true for each of them.

-- ─────────────────────────────────────────────────────────────────────────────
-- One order per demo reviewer who wrote at least one review.
--
-- Billing details are written in CLEAR TEXT here and encrypted on the next
-- application boot by PiiEncryptionBackfill. A migration cannot encrypt them:
-- the key lives in the application and deliberately never in the database.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO orders (
    id, user_id, idempotency_key,
    billing_name, billing_email, billing_phone,
    billing_address_line1, billing_city, billing_postal_code, billing_country,
    currency, subtotal, vat_total, total,
    delivery_code, delivery_name, delivery_price,
    delivery_lead_days_min, delivery_lead_days_max,
    created_at
)
SELECT
    gen_random_uuid(),
    u.id,
    'seed-order-' || u.id,
    COALESCE(u.display_name, 'Demo Customer'),
    u.email,
    '+372 5555 ' || lpad((row_number() OVER (ORDER BY u.email))::text, 4, '0'),
    'Pikk tn ' || (1 + (row_number() OVER (ORDER BY u.email))::int % 40),
    'Tallinn',
    '10123',
    'EE',
    'EUR',
    0, 0, 0,           -- recomputed from the lines below
    'omniva_parcel', 'Omniva parcel machine', 3.49, 1, 3,
    -- Comfortably before the reviews, so nobody reviewed a product the day
    -- before it was delivered.
    now() - interval '120 days'
FROM users u
WHERE u.email LIKE 'reviewer%@example.ee'
  AND EXISTS (SELECT 1 FROM reviews r WHERE r.user_id = u.id)
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.idempotency_key = 'seed-order-' || u.id);

-- ─────────────────────────────────────────────────────────────────────────────
-- One line per product that reviewer reviewed.
--
-- Prices and names are SNAPSHOTTED from the product as it is now, which is what
-- a real order line does (DECISIONS.md §2). The VAT rate is taken from the
-- product's own vat_category rather than hardcoded, because a hardcoded rate is
-- exactly what this project forbids: Estonia moved 20% -> 22% -> 24% in 18 months.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO order_lines (
    order_id, product_id, product_name, unit_price, vat_rate, quantity, currency, line_total
)
SELECT
    o.id,
    p.id,
    p.name,
    p.price,
    CASE p.vat_category
        WHEN 'STANDARD'   THEN 0.2400
        WHEN 'REDUCED_13' THEN 0.1300
        WHEN 'REDUCED_9'  THEN 0.0900
        ELSE 0.0000
    END,
    1,
    p.currency,
    p.price
FROM orders o
JOIN users u   ON u.id = o.user_id
JOIN reviews r ON r.user_id = u.id
JOIN products p ON p.id = r.product_id
WHERE o.idempotency_key = 'seed-order-' || u.id
  AND NOT EXISTS (SELECT 1 FROM order_lines ol WHERE ol.order_id = o.id);

-- Totals derived from the lines, never typed in twice.
-- Prices are VAT-INCLUSIVE (gross), matching products.price and the rest of the
-- codebase, so net is back-calculated rather than added on top.
UPDATE orders o
   SET total    = sub.gross,
       vat_total = sub.vat,
       subtotal = sub.gross - sub.vat
  FROM (
        SELECT ol.order_id,
               SUM(ol.line_total) AS gross,
               SUM(ROUND(ol.line_total - (ol.line_total / (1 + ol.vat_rate)), 2)) AS vat
          FROM order_lines ol
         GROUP BY ol.order_id
  ) sub
 WHERE o.id = sub.order_id
   AND o.idempotency_key LIKE 'seed-order-%';

-- Add the delivery fee to the total AFTER the lines are summed — it is not an
-- order line, so it is not in the sum above.
UPDATE orders SET total = total + COALESCE(delivery_price, 0)
 WHERE idempotency_key LIKE 'seed-order-%';

-- ─────────────────────────────────────────────────────────────────────────────
-- The event stream. Status is DERIVED by folding these (DECISIONS.md §1), so an
-- order without events reads as having no status at all — CREATED then PAID is
-- what makes these orders count as purchases.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO order_events (order_id, event_type, occurred_at)
SELECT o.id, 'CREATED', o.created_at
  FROM orders o
 WHERE o.idempotency_key LIKE 'seed-order-%'
   AND NOT EXISTS (SELECT 1 FROM order_events e WHERE e.order_id = o.id AND e.event_type = 'CREATED');

INSERT INTO order_events (order_id, event_type, occurred_at)
SELECT o.id, 'PAID', o.created_at + interval '2 minutes'
  FROM orders o
 WHERE o.idempotency_key LIKE 'seed-order-%'
   AND NOT EXISTS (SELECT 1 FROM order_events e WHERE e.order_id = o.id AND e.event_type = 'PAID');
