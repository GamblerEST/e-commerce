-- V15__reviews.sql
-- Product reviews. Two rules shape this schema:
--
-- 1. Only a VERIFIED PURCHASER may review — the caller must own an order
--    containing this product that actually reached PAID. That check needs to
--    fold `order_events` (status is derived, never stored — DECISIONS.md §1),
--    which is more than a CHECK constraint can express, so it lives in
--    ReviewService. The UNIQUE constraint below is the part the database CAN
--    enforce, and does.
--
-- 2. `products.avg_rating` / `products.review_count` are DENORMALIZED
--    (V3__catalog.sql, already read by faceted search's RATING_DESC sort).
--    They are maintained by the trigger at the bottom of this file rather
--    than by application code, for the same reason anonymize_user() lives in
--    the database (DECISIONS.md §5): a derivation that must never drift
--    cannot depend on every future code path remembering to call it.

CREATE TABLE reviews (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,

    -- Plain FK, no ON DELETE: users are ANONYMIZED, never deleted
    -- (DECISIONS.md §5), so this row always survives. A review by an
    -- anonymized author keeps its content and loses its byline naturally —
    -- anonymize_user() nulls users.display_name, and the API renders a
    -- missing display name as "Anonymous". No separate scrubbing path.
    user_id       UUID NOT NULL REFERENCES users(id),

    rating        SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title         TEXT CHECK (title IS NULL OR length(title) <= 150),
    body          TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),

    -- Reviews publish immediately; moderation is after-the-fact takedown,
    -- not approve-before-visible. A pending-first queue would mean a real
    -- customer's honest review silently doesn't appear until someone gets
    -- around to it, which is worse for both sides. HIDDEN reviews stop
    -- counting toward the product's rating (see the trigger below).
    status        TEXT NOT NULL DEFAULT 'PUBLISHED'
                  CHECK (status IN ('PUBLISHED', 'HIDDEN')),

    -- Denormalized, same reasoning as products.avg_rating — a review list
    -- sorted by helpfulness must not COUNT() review_votes per row.
    -- Maintained by the trigger on review_votes below.
    helpful_count INT NOT NULL DEFAULT 0 CHECK (helpful_count >= 0),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One review per person per product. This is the honest-user guard AND the
-- race guard: two concurrent submits from the same person can't both land,
-- because the second INSERT hits this index rather than a check-then-act
-- read that both requests passed (same principle as orders.idempotency_key).
CREATE UNIQUE INDEX reviews_one_per_user_per_product ON reviews (product_id, user_id);

-- The product page's own query: published reviews for one product, newest
-- or most-helpful first.
CREATE INDEX reviews_product_idx ON reviews (product_id, status, created_at DESC);
-- The moderation queue's query: everything, newest first, filterable by status.
CREATE INDEX reviews_moderation_idx ON reviews (status, created_at DESC);

CREATE TRIGGER reviews_touch_updated_at
    BEFORE UPDATE ON reviews
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ─────────────────────────────────────────────────────────────────────────────
-- review_votes — "was this helpful?", one vote per person per review.
-- A separate table rather than a counter column because the counter alone
-- can't answer "have I already voted?", and without that question a single
-- user can inflate a review indefinitely by clicking twice.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE review_votes (
    review_id  UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Denormalization triggers. Both recompute from the source rows rather than
-- incrementing a counter: an increment is only correct if it fires exactly
-- once per change forever, while a recompute is self-healing — it lands on
-- the right number even after a bug, a manual SQL fix, or a restored backup.
-- The row counts here are per-product/per-review, not table-wide, so this
-- stays cheap.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION refresh_product_rating(target_product_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE products p
       SET avg_rating   = sub.avg_rating,
           review_count = sub.review_count
      FROM (
        -- COALESCE matters: AVG() over zero rows is NULL, and avg_rating is
        -- NOT NULL DEFAULT 0 (V3__catalog.sql). Without it, hiding or
        -- deleting a product's LAST published review fails the whole
        -- statement on a not-null violation — an edge case a two-review
        -- test never reaches, and a one-review shop hits immediately.
        SELECT COALESCE(ROUND(AVG(rating)::numeric, 2), 0) AS avg_rating,
               COUNT(*)                                    AS review_count
          FROM reviews
         WHERE product_id = target_product_id
           AND status = 'PUBLISHED'
      ) sub
     WHERE p.id = target_product_id;
END;
$$;

CREATE OR REPLACE FUNCTION reviews_refresh_product_rating()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- On UPDATE the review can move BETWEEN products only in theory (the API
    -- never allows it), but handling OLD and NEW separately costs nothing and
    -- means a future admin "move this review" feature can't silently corrupt
    -- two products' ratings at once.
    IF (TG_OP = 'DELETE' OR TG_OP = 'UPDATE') THEN
        PERFORM refresh_product_rating(OLD.product_id);
    END IF;
    IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE') THEN
        PERFORM refresh_product_rating(NEW.product_id);
    END IF;
    RETURN NULL; -- AFTER trigger, return value is ignored
END;
$$;

CREATE TRIGGER reviews_maintain_product_rating
    AFTER INSERT OR UPDATE OR DELETE ON reviews
    FOR EACH ROW EXECUTE FUNCTION reviews_refresh_product_rating();

CREATE OR REPLACE FUNCTION review_votes_refresh_helpful_count()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target UUID := COALESCE(NEW.review_id, OLD.review_id);
BEGIN
    UPDATE reviews r
       SET helpful_count = (SELECT COUNT(*) FROM review_votes v WHERE v.review_id = target)
     WHERE r.id = target;
    RETURN NULL;
END;
$$;

CREATE TRIGGER review_votes_maintain_helpful_count
    AFTER INSERT OR DELETE ON review_votes
    FOR EACH ROW EXECUTE FUNCTION review_votes_refresh_helpful_count();

-- No GRANTs needed: V2__least_privilege.sql's ALTER DEFAULT PRIVILEGES
-- already covers tables created by later migrations. Only an append-only
-- table (audit_log) needs its narrower grants set explicitly.
