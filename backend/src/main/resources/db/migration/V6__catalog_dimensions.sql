-- V6__catalog_dimensions.sql
-- Backfills length_cm/width_cm/height_cm for products seeded in V4/V5 that
-- only ever got weight_kg (87% of the catalog) — realistic packaged
-- dimensions per product type, so the metric+imperial dimension display
-- (Dimensions DTO, computed via UnitConverter) has something to show on
-- almost any product someone clicks into, not just the seven laptops
-- that happened to get dimensions in the original seed.

-- ── Phones — small boxed devices ───────────────────────────────────────────
UPDATE products SET length_cm = 16.5, width_cm = 8.0, height_cm = 1.2
    WHERE slug IN ('aurora-x12-smartphone', 'aurora-x12-smartphone-blue', 'aurora-x12-smartphone-silver');
UPDATE products SET length_cm = 15.8, width_cm = 7.6, height_cm = 1.0
    WHERE slug = 'aurora-x8-lite';
UPDATE products SET length_cm = 14.5, width_cm = 7.0, height_cm = 0.9
    WHERE slug = 'aurora-flagship-mini';
UPDATE products SET length_cm = 16.2, width_cm = 7.8, height_cm = 1.1
    WHERE slug = 'aurora-flagship-pro';
UPDATE products SET length_cm = 15.0, width_cm = 7.2, height_cm = 1.0
    WHERE slug IN ('northline-pocket-phone', 'northline-pocket-phone-red');
UPDATE products SET length_cm = 17.0, width_cm = 8.3, height_cm = 1.2
    WHERE slug = 'northline-max';

-- ── Laptops — the one straggler missing from the original seed ────────────
UPDATE products SET length_cm = 35.8, width_cm = 24.7, height_cm = 1.9
    WHERE slug = 'workhorse-15-pro';

-- ── Books — hardcover vs paperback trim size ───────────────────────────────
UPDATE products SET length_cm = 23.5, width_cm = 15.6, height_cm = 2.5
    WHERE slug IN ('databases-at-scale', 'foundations-of-databases', 'the-beginners-kitchen', 'the-quiet-harbor-hardcover');
UPDATE products SET length_cm = 19.8, width_cm = 12.9, height_cm = 1.8
    WHERE slug IN ('foundations-of-databases-paperback', 'growing-wild-a-garden-year', 'the-long-coastline', 'the-quiet-harbor', 'tides-of-tallinn');

-- ── Men's clothing — folded/boxed ──────────────────────────────────────────
UPDATE products SET length_cm = 30, width_cm = 24, height_cm = 2
    WHERE slug IN ('classic-crew-tshirt-s', 'classic-crew-tshirt-m', 'classic-crew-tshirt-l', 'classic-crew-tshirt-charcoal-m');
UPDATE products SET length_cm = 38, width_cm = 30, height_cm = 6
    WHERE slug IN ('weekend-denim-jacket', 'weekend-denim-jacket-l');
UPDATE products SET length_cm = 32, width_cm = 26, height_cm = 4
    WHERE slug = 'tapered-chino-trousers';
UPDATE products SET length_cm = 34, width_cm = 27, height_cm = 5
    WHERE slug = 'merino-wool-sweater';
UPDATE products SET length_cm = 40, width_cm = 32, height_cm = 7
    WHERE slug = 'rainproof-field-coat';
UPDATE products SET length_cm = 33, width_cm = 21, height_cm = 12
    WHERE slug = 'everyday-sneakers-mens';

-- ── Women's clothing ────────────────────────────────────────────────────────
UPDATE products SET length_cm = 32, width_cm = 25, height_cm = 3
    WHERE slug IN ('everyday-wrap-dress', 'everyday-wrap-dress-m', 'everyday-wrap-dress-deepblue-s');
UPDATE products SET length_cm = 36, width_cm = 28, height_cm = 5
    WHERE slug IN ('trail-running-jacket', 'trail-running-jacket-blue-l');
UPDATE products SET length_cm = 32, width_cm = 26, height_cm = 4
    WHERE slug = 'high-rise-jeans';
UPDATE products SET length_cm = 25, width_cm = 20, height_cm = 3
    WHERE slug = 'cashmere-blend-scarf';
UPDATE products SET length_cm = 34, width_cm = 27, height_cm = 6
    WHERE slug = 'quilted-puffer-vest';
UPDATE products SET length_cm = 31, width_cm = 19, height_cm = 11
    WHERE slug = 'everyday-sneakers-womens';

-- ── Home & Garden — varies most by item ─────────────────────────────────────
UPDATE products SET length_cm = 24, width_cm = 18, height_cm = 20
    WHERE slug IN ('ceramic-pour-over-set', 'ceramic-pour-over-set-blue');
UPDATE products SET length_cm = 120, width_cm = 90, height_cm = 10
    WHERE slug IN ('raised-garden-bed-kit', 'raised-garden-bed-kit-large');
UPDATE products SET length_cm = 32, width_cm = 32, height_cm = 6
    WHERE slug = 'cast-iron-skillet';
UPDATE products SET length_cm = 35, width_cm = 28, height_cm = 5
    WHERE slug = 'linen-throw-blanket';
UPDATE products SET length_cm = 40, width_cm = 15, height_cm = 10
    WHERE slug = 'solar-garden-lights-set-of-6';
UPDATE products SET length_cm = 40, width_cm = 30, height_cm = 6
    WHERE slug = 'bamboo-cutting-board-set';
UPDATE products SET length_cm = 30, width_cm = 20, height_cm = 18
    WHERE slug = 'ceramic-planter-trio';
