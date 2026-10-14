-- V19__seed_real_reviews.sql
--
-- Replaces FABRICATED rating figures with real, derived ones.
--
-- The problem this fixes
-- ─────────────────────
-- V4/V5 seeded products.avg_rating and products.review_count directly, as
-- decorative numbers. Every one of the 54 seeded products advertised a rating
-- ("4.9 from 205 reviews") while the reviews table was empty. Two things were
-- wrong with that, and the second is serious:
--
--   1. The shop claimed review counts it could not show. Clicking into the
--      reviews of a product rated "4.9 (205)" found nothing there.
--   2. V15__reviews.sql installs a trigger that RECOMPUTES avg_rating and
--      review_count from the reviews table on every insert/update/delete. So
--      the first genuine review posted against any product would not nudge its
--      4.9 average — it would REPLACE it outright, collapsing "4.9 from 205"
--      to that single reviewer's score. The seeded numbers were not just
--      cosmetic, they were a trap primed to fire on first real use.
--
-- The fix is to make the displayed rating true: seed real review rows written
-- by real (demo) accounts, and let the V15 trigger derive avg_rating and
-- review_count from them, exactly as it will in production.
--
-- Honest note on the purchase check
-- ─────────────────────────────────
-- ReviewService enforces that only a verified purchaser may review. This seed
-- inserts rows directly and therefore bypasses that check, purely so a fresh
-- clone has a demonstrable catalogue. The rule still holds for every review
-- submitted through the API — which is where it matters, and where it is
-- tested (ReviewControllerTest). Seeding matching PAID orders for 150+ reviews
-- would add a large amount of fake financial history to the orders table, and
-- fake money is a worse lie than fake reviews.

-- ─────────────────────────────────────────────────────────────────────────────
-- Demo reviewers.
--
-- password_hash is NULL, so none of these accounts can log in: NULL never
-- matches an Argon2id verification. They exist to own review rows, not to be
-- used. email_verified_at is set so they look like settled accounts rather
-- than a pile of unverified signups in the admin user list.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO users (email, display_name, role, email_verified_at, created_at)
SELECT
    'reviewer' || n || '@example.ee',
    (ARRAY['Maarja','Toomas','Kristi','Andres','Liina','Priit','Kadri','Margus',
           'Triin','Rein','Eve','Jaanus','Piret','Urmas','Katrin','Tarmo',
           'Helen','Siim','Anu','Marko'])[((n - 1) % 20) + 1]
        || ' ' ||
    (ARRAY['T.','K.','L.','M.','R.','S.','V.','P.'])[((n - 1) % 8) + 1],
    'CUSTOMER',
    now() - (n || ' days')::interval,
    now() - (n || ' days')::interval
FROM generate_series(1, 20) AS n;

-- ─────────────────────────────────────────────────────────────────────────────
-- Reviews.
--
-- Between 2 and 6 per product, deterministically derived from the product row
-- so the seed is reproducible rather than random on every fresh database.
-- Ratings skew high because a real catalogue of curated goods does; see the
-- weighted CASE below for why a uniform spread was not good enough.
-- ─────────────────────────────────────────────────────────────────────────────
WITH numbered_products AS (
    SELECT id, name, row_number() OVER (ORDER BY id) AS pn
      FROM products
     WHERE deleted_at IS NULL
),
numbered_users AS (
    SELECT id, row_number() OVER (ORDER BY email) AS un
      FROM users
     WHERE email LIKE 'reviewer%@example.ee'
),
pairs AS (
    SELECT p.id AS product_id,
           p.name,
           p.pn,
           -- 2..6 reviews per product
           generate_series(1, 2 + (p.pn % 5)) AS slot
      FROM numbered_products p
)
INSERT INTO reviews (product_id, user_id, rating, title, body, helpful_count, created_at)
SELECT
    pairs.product_id,
    u.id,
    rating.value,
    (ARRAY['Exactly as described',
           'Good buy',
           'Very pleased',
           'Does the job',
           'Better than expected',
           'Solid quality'])[((pairs.pn + pairs.slot) % 6) + 1],
    CASE rating.value
        WHEN 5 THEN 'Arrived quickly and the quality is genuinely better than I expected for the price. '
                    || 'I have used it daily for a few weeks now with no complaints at all.'
        WHEN 4 THEN 'Happy with this overall. It does what it says and feels well made. '
                    || 'Knocking off a star only because the packaging was more than it needed to be.'
        ELSE 'Reasonable for the money. It works fine, though it is a little lighter in '
             || 'the hand than the photos suggest. I would probably buy it again.'
    END,
    0, -- recomputed from review_votes by the V15 trigger below
    now() - ((pairs.pn * 3 + pairs.slot) || ' days')::interval
FROM pairs
JOIN numbered_users u
  -- Rotating offset per product, so the same 20 accounts spread across the
  -- catalogue without any account reviewing one product twice (which the
  -- reviews_one_per_user_per_product unique index would reject anyway).
  ON u.un = ((pairs.pn * 7 + pairs.slot * 3) % 20) + 1
CROSS JOIN LATERAL (
    -- Weighted, not uniform. A flat 3/4/5 cycle averaged almost every product
    -- to exactly 4.00 (32 of 54 identical), which makes "sort by rating" and
    -- the home page's best-rated ordering meaningless — there is nothing to
    -- order. This maps a deterministic 0-9 hash to a realistic curve for a
    -- curated catalogue: mostly 4-5, some 3, occasionally 2. Multiplying the
    -- product number by a coprime shifts each product to a different phase, so
    -- averages actually differ between products.
    SELECT (CASE ((pairs.pn * 7 + pairs.slot * 11) % 10)
                WHEN 0 THEN 5 WHEN 1 THEN 5 WHEN 2 THEN 5 WHEN 3 THEN 5
                WHEN 4 THEN 4 WHEN 5 THEN 4 WHEN 6 THEN 4
                WHEN 7 THEN 3 WHEN 8 THEN 3
                ELSE 2
            END)::smallint AS value
) AS rating
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- Helpful votes.
--
-- Needed for more than decoration: reviews must be sortable
-- by helpfulness, and that ordering is untestable and undemonstrable when
-- every review has zero votes. Older reviews collect more votes, which is what
-- actually happens.
-- ─────────────────────────────────────────────────────────────────────────────
WITH numbered_reviews AS (
    SELECT id, user_id, row_number() OVER (ORDER BY created_at) AS rn
      FROM reviews
),
numbered_users AS (
    SELECT id, row_number() OVER (ORDER BY email) AS un
      FROM users
     WHERE email LIKE 'reviewer%@example.ee'
)
INSERT INTO review_votes (review_id, user_id)
SELECT r.id, u.id
  FROM numbered_reviews r
  CROSS JOIN generate_series(1, (r.rn % 7)) AS v
  JOIN numbered_users u ON u.un = ((r.rn + v * 5) % 20) + 1
 -- Nobody upvotes their own review; the API forbids it, so the seed must not
 -- create data the API would have refused.
 WHERE u.id <> r.user_id
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- Force the denormalised columns to agree with the rows just inserted.
--
-- The V15 triggers already fired per row above, so this is belt-and-braces
-- rather than strictly required — but it also CORRECTS any product that got no
-- seeded reviews, whose fabricated 4.9/205 would otherwise survive untouched.
-- After this statement, every rating in the shop is derived from real rows.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE products p
   SET avg_rating   = COALESCE(sub.avg_rating, 0),
       review_count = COALESCE(sub.review_count, 0)
  FROM (
        SELECT pr.id AS product_id,
               ROUND(AVG(r.rating)::numeric, 2) AS avg_rating,
               COUNT(r.id)                      AS review_count
          FROM products pr
          LEFT JOIN reviews r
                 ON r.product_id = pr.id AND r.status = 'PUBLISHED'
         GROUP BY pr.id
  ) sub
 WHERE p.id = sub.product_id;
