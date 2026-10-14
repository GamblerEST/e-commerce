-- V18__home_featured.sql
-- Home page: showcases featured products and collections.
--
-- Both flags are CURATED, not derived. The tempting shortcut is to define
-- "featured" as best-selling or highest-rated and compute it — but then the
-- shop has no way to push a new line, clear seasonal stock, or honour a
-- supplier deal, which is the entire commercial purpose of a home page. A
-- boolean an admin controls is the honest model; ordering within it can still
-- fall back to rating.

-- ─────────────────────────────────────────────────────────────────────────────
-- Featured products
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE products ADD COLUMN featured BOOLEAN NOT NULL DEFAULT false;

-- PARTIAL index: the home page asks for the handful of featured, live products
-- and nothing else ever filters on this column. Indexing only the `true` rows
-- keeps it a few pages regardless of catalogue size, and excluding soft-deleted
-- rows here means the home query never has to look at a deleted product at all.
CREATE INDEX products_featured_idx
    ON products (avg_rating DESC, created_at DESC)
    WHERE featured AND deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Collections
--
-- A "collection" is a top-level category an admin has chosen to promote. This
-- deliberately does NOT introduce a second grouping concept alongside
-- categories: two overlapping taxonomies would immediately raise "which one
-- owns this product", and every product would need maintaining in both. A
-- collection is a category wearing a badge.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE categories ADD COLUMN featured BOOLEAN NOT NULL DEFAULT false;

-- Short marketing line under the collection title on the home page. Nullable:
-- a collection without one renders its name alone rather than placeholder text.
ALTER TABLE categories ADD COLUMN tagline TEXT
    CHECK (tagline IS NULL OR length(tagline) <= 200);

CREATE INDEX categories_featured_idx ON categories (name) WHERE featured;

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: the home page must not be empty on a fresh clone, or anyone
-- following the README's quick start sees a broken-looking shop. Picks the
-- best-rated live product per top-level category (at most 8), and promotes
-- every top-level category that actually has products behind it.
--
-- This runs once, at migration time, purely to make the seeded catalogue
-- presentable. It is not the application's featured-product logic — that is
-- the admin toggle above.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE products SET featured = true
 WHERE id IN (
    SELECT DISTINCT ON (category_id) id
      FROM products
     WHERE deleted_at IS NULL
     ORDER BY category_id, avg_rating DESC, review_count DESC, created_at DESC
     LIMIT 8
 );

UPDATE categories c SET featured = true
 WHERE c.parent_id IS NULL
   AND EXISTS (
       -- Direct children's products count too: a top-level category is
       -- usually a container ("Electronics") whose products hang off its
       -- subcategories, so an EXISTS on its own product rows alone would
       -- promote nothing on a realistically-shaped catalogue.
       SELECT 1 FROM products p
        WHERE p.deleted_at IS NULL
          AND (p.category_id = c.id
               OR p.category_id IN (SELECT id FROM categories WHERE parent_id = c.id))
   );
