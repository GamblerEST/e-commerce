-- V3__catalog.sql
-- Product catalog: categories, products, images, EAV attributes for facets.
--
-- Stock quantity here is a SIMPLE display counter ("12 in stock" / "out of
-- stock" on a listing page) — it is NOT the checkout-safe inventory system.
-- A later migration replaces oversell-prevention with a proper
-- on_hand/reserved reservation model (DECISIONS.md §3); this column has no
-- reservation semantics and must never be decremented directly at checkout.
--
-- Weight/dimensions are stored ONLY in metric (kg, cm) — a single canonical
-- unit avoids two columns silently drifting out of sync. Imperial values are
-- computed at the API layer (ProductResponse) from these, never stored.

-- ─────────────────────────────────────────────────────────────────────────────
-- categories — self-referential for subcategories
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES categories(id) ON DELETE SET NULL,
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX categories_slug_unique ON categories (slug);
CREATE INDEX categories_parent_idx ON categories (parent_id);

CREATE TRIGGER categories_touch_updated_at
    BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ─────────────────────────────────────────────────────────────────────────────
-- products
--
-- deleted_at, not DELETE: a product referenced by a historical order_line
-- must keep existing for the order to make sense (DECISIONS.md §2 — order
-- lines snapshot name/price at purchase time and only keep product_id for
-- "show me this product again"; a hard-deleted product breaks that link).
-- Soft-delete keeps the row addressable while removing it from listings.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE products (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id     UUID REFERENCES categories(id) ON DELETE SET NULL,

    name            TEXT NOT NULL,
    slug            TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    brand           TEXT,

    -- Money: NUMERIC + explicit ISO-4217 currency, never a float.
    price           NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'EUR',

    -- Display-only counter — see file header.
    stock_quantity  INT NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),

    weight_kg       NUMERIC(10,3) CHECK (weight_kg IS NULL OR weight_kg >= 0),
    length_cm       NUMERIC(10,2) CHECK (length_cm IS NULL OR length_cm >= 0),
    width_cm        NUMERIC(10,2) CHECK (width_cm  IS NULL OR width_cm  >= 0),
    height_cm       NUMERIC(10,2) CHECK (height_cm IS NULL OR height_cm >= 0),

    -- Denormalized from the review system — updated when reviews land,
    -- never computed per-query.
    avg_rating      NUMERIC(3,2) NOT NULL DEFAULT 0 CHECK (avg_rating BETWEEN 0 AND 5),
    review_count    INT NOT NULL DEFAULT 0 CHECK (review_count >= 0),

    deleted_at      TIMESTAMPTZ,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only live products occupy a slug — the same partial-index pattern the
-- users table uses (V1), so a soft-deleted product's slug can be reused.
CREATE UNIQUE INDEX products_slug_unique ON products (slug) WHERE deleted_at IS NULL;

CREATE INDEX products_category_idx ON products (category_id) WHERE deleted_at IS NULL;
CREATE INDEX products_brand_idx    ON products (brand)       WHERE deleted_at IS NULL;
CREATE INDEX products_price_idx    ON products (price)        WHERE deleted_at IS NULL;

-- Faceted sort by rating and keyset pagination both need a stable, indexed
-- ordering. (id breaks ties for keyset pagination — see ProductRepository.)
CREATE INDEX products_rating_idx ON products (avg_rating DESC, id) WHERE deleted_at IS NULL;

CREATE TRIGGER products_touch_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- pg_trgm: fast substring/similarity search for type-ahead suggestions and
-- free-text search, without a separate search engine. Same tool this
-- project already reaches for elsewhere (V1's comment on citext/indexing
-- patterns).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX products_name_trgm_idx ON products USING gin (name gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────
-- product_images
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE product_images (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id     UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    -- Path under the static image-serving root (see CatalogController /
    -- application static resource mapping) — not an external URL.
    storage_key    TEXT NOT NULL,
    alt_text       TEXT,
    display_order  INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX product_images_product_idx ON product_images (product_id, display_order);

-- ─────────────────────────────────────────────────────────────────────────────
-- product_attributes — EAV for facets (color, size, material, ...)
--
-- EAV over a fixed-column schema because facets are open-ended and vary by
-- category (a book has no "size", a t-shirt has no "page count"). One table
-- with a name/value pair beats a wide sparse table or a schema migration
-- every time a new attribute type is needed.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE product_attributes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    attribute_name  TEXT NOT NULL,
    attribute_value TEXT NOT NULL
);

-- Powers "show me all values of color across the current result set" AND
-- "filter by color=red" — both directions of faceted search.
CREATE INDEX product_attributes_name_value_idx ON product_attributes (attribute_name, attribute_value);
CREATE INDEX product_attributes_product_idx ON product_attributes (product_id);
