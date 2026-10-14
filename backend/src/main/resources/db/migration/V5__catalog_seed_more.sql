-- V5__catalog_seed_more.sql
-- More demo products so keyset pagination has multiple pages to walk
-- (V4 seeded 14 — not enough to exercise a page boundary against the
-- frontend's page size of 20). Looks up category ids by slug rather than
-- re-declaring them: V4 already created the category tree, and migrations
-- are append-only/immutable — this only adds rows, never touches V4.

DO $$
DECLARE
    cat_phones   UUID := (SELECT id FROM categories WHERE slug = 'phones');
    cat_laptops  UUID := (SELECT id FROM categories WHERE slug = 'laptops');
    cat_mens     UUID := (SELECT id FROM categories WHERE slug = 'mens');
    cat_womens   UUID := (SELECT id FROM categories WHERE slug = 'womens');
    cat_books    UUID := (SELECT id FROM categories WHERE slug = 'books');
    cat_home     UUID := (SELECT id FROM categories WHERE slug = 'home-garden');
    p UUID;
BEGIN
    -- ── Phones ──────────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Aurora X12 Smartphone', 'aurora-x12-smartphone-blue', 'A capable mid-range smartphone with a 6.5" display.', 'Aurora', 429.00, 'EUR', 28, 0.189, 4.20, 97)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Ocean Blue'), (p, 'storage', '128GB'), (p, 'screen_size', '6.5"');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Aurora X12 Smartphone (Blue)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Aurora X8 Lite', 'aurora-x8-lite', 'An entry-level smartphone with all-day battery life.', 'Aurora', 249.00, 'EUR', 55, 0.172, 3.90, 61)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Graphite'), (p, 'storage', '64GB'), (p, 'screen_size', '6.1"');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Aurora X8 Lite', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Northline Pocket Phone', 'northline-pocket-phone-red', 'A compact, affordable phone for everyday use.', 'Northline', 199.00, 'EUR', 33, 0.152, 3.80, 41)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Red'), (p, 'storage', '64GB');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Northline Pocket Phone (Red)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Northline Max', 'northline-max', 'A large-screen phone built for media and multitasking.', 'Northline', 289.00, 'EUR', 19, 0.201, 4.00, 28)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Black'), (p, 'storage', '128GB'), (p, 'screen_size', '6.8"');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Northline Max', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Aurora Flagship Mini', 'aurora-flagship-mini', 'A compact flagship with a pro-grade camera.', 'Aurora', 699.00, 'EUR', 12, 0.164, 4.70, 54)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Titanium'), (p, 'storage', '256GB'), (p, 'screen_size', '6.1"');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Aurora Flagship Mini', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Aurora Flagship Pro', 'aurora-flagship-pro', 'A flagship phone with a 120Hz display and 5x zoom.', 'Aurora', 949.00, 'EUR', 8, 0.198, 4.80, 112)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Titanium'), (p, 'storage', '512GB'), (p, 'screen_size', '6.7"');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Aurora Flagship Pro', 0);

    -- ── Laptops ─────────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, length_cm, width_cm, height_cm, avg_rating, review_count)
        VALUES (cat_laptops, 'Vantage 13 Air', 'vantage-13-air', 'An ultra-light 13" laptop for everyday carry.', 'Vantage', 949.00, 'EUR', 21, 1.05, 29.7, 20.9, 1.2, 4.50, 143)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Silver'), (p, 'ram', '8GB'), (p, 'storage', '256GB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Vantage 13 Air', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, length_cm, width_cm, height_cm, avg_rating, review_count)
        VALUES (cat_laptops, 'Workhorse 17 Studio', 'workhorse-17-studio', 'A 17" creator laptop with a color-accurate display.', 'Vantage', 2199.00, 'EUR', 4, 2.4, 38.0, 26.0, 1.8, 4.90, 47)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Space Gray'), (p, 'ram', '64GB'), (p, 'storage', '2TB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Workhorse 17 Studio', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, length_cm, width_cm, height_cm, avg_rating, review_count)
        VALUES (cat_laptops, 'Northline Notebook 14', 'northline-notebook-14', 'A budget-friendly 14" laptop for students.', 'Northline', 549.00, 'EUR', 37, 1.6, 32.4, 21.9, 1.9, 3.70, 88)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Black'), (p, 'ram', '8GB'), (p, 'storage', '256GB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Northline Notebook 14', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, length_cm, width_cm, height_cm, avg_rating, review_count)
        VALUES (cat_laptops, 'Aurora Book Flex', 'aurora-book-flex', 'A 2-in-1 convertible laptop with a touch display.', 'Aurora', 899.00, 'EUR', 16, 1.42, 31.0, 21.6, 1.6, 4.10, 39)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Silver'), (p, 'ram', '16GB'), (p, 'storage', '512GB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Aurora Book Flex', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, length_cm, width_cm, height_cm, avg_rating, review_count)
        VALUES (cat_laptops, 'Workhorse 15 Gaming', 'workhorse-15-gaming', 'A 15" gaming laptop with a 165Hz display.', 'Vantage', 1799.00, 'EUR', 0, 2.3, 35.9, 26.2, 2.2, 4.40, 71)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Black'), (p, 'ram', '32GB'), (p, 'storage', '1TB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Workhorse 15 Gaming', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, length_cm, width_cm, height_cm, avg_rating, review_count)
        VALUES (cat_laptops, 'Northline Notebook 14 Plus', 'northline-notebook-14-plus', 'The Notebook 14 with double the storage.', 'Northline', 649.00, 'EUR', 24, 1.62, 32.4, 21.9, 1.9, 3.90, 35)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Silver'), (p, 'ram', '16GB'), (p, 'storage', '512GB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Northline Notebook 14 Plus', 0);

    -- ── Men's clothing ──────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Classic Crew T-Shirt', 'classic-crew-tshirt-s', 'A soft, breathable everyday t-shirt.', 'Fieldstone', 19.90, 'EUR', 80, 0.17, 4.20, 210)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Navy'), (p, 'size', 'S'), (p, 'material', 'Cotton');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Classic Crew T-Shirt', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Classic Crew T-Shirt', 'classic-crew-tshirt-charcoal-m', 'A soft, breathable everyday t-shirt.', 'Fieldstone', 19.90, 'EUR', 64, 0.18, 4.30, 88)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Charcoal'), (p, 'size', 'M'), (p, 'material', 'Cotton');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Classic Crew T-Shirt (Charcoal)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Weekend Denim Jacket', 'weekend-denim-jacket-l', 'A durable denim jacket for cooler days.', 'Fieldstone', 79.00, 'EUR', 18, 0.88, 4.50, 56)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Indigo'), (p, 'size', 'L'), (p, 'material', 'Denim');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Weekend Denim Jacket (L)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Tapered Chino Trousers', 'tapered-chino-trousers', 'Smart-casual chinos with a tapered fit.', 'Fieldstone', 45.00, 'EUR', 52, 0.42, 4.10, 74)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Khaki'), (p, 'size', 'M'), (p, 'material', 'Cotton Twill');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Tapered Chino Trousers', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Merino Wool Sweater', 'merino-wool-sweater', 'A fine-knit merino sweater for layering.', 'Meridian', 89.00, 'EUR', 27, 0.38, 4.60, 39)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Charcoal'), (p, 'size', 'L'), (p, 'material', 'Merino Wool');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Merino Wool Sweater', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Everyday Sneakers', 'everyday-sneakers-mens', 'Lightweight everyday sneakers with cushioned soles.', 'Fieldstone', 65.00, 'EUR', 40, 0.62, 4.30, 121)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'White'), (p, 'size', '42'), (p, 'material', 'Canvas');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Everyday Sneakers', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Rainproof Field Coat', 'rainproof-field-coat', 'A waterproof field coat for wet climates.', 'Meridian', 135.00, 'EUR', 0, 0.95, 4.70, 22)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Olive'), (p, 'size', 'L'), (p, 'material', 'Recycled Nylon');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Rainproof Field Coat', 0);

    -- ── Women's clothing ────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Everyday Wrap Dress', 'everyday-wrap-dress-m', 'A flattering wrap dress for any occasion.', 'Meridian', 59.00, 'EUR', 31, 0.33, 4.70, 143)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Emerald'), (p, 'size', 'M'), (p, 'material', 'Viscose');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Everyday Wrap Dress (M)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Everyday Wrap Dress', 'everyday-wrap-dress-deepblue-s', 'A flattering wrap dress for any occasion.', 'Meridian', 59.00, 'EUR', 26, 0.32, 4.50, 67)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Deep Blue'), (p, 'size', 'S'), (p, 'material', 'Viscose');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Everyday Wrap Dress (Deep Blue)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Trail Running Jacket', 'trail-running-jacket-blue-l', 'A lightweight, packable running jacket.', 'Meridian', 89.00, 'EUR', 14, 0.29, 4.40, 77)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Sky Blue'), (p, 'size', 'L'), (p, 'material', 'Recycled Polyester');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Trail Running Jacket (Blue)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'High-Rise Jeans', 'high-rise-jeans', 'Comfort-stretch high-rise jeans.', 'Fieldstone', 55.00, 'EUR', 44, 0.48, 4.20, 96)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Mid Blue'), (p, 'size', 'M'), (p, 'material', 'Denim');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'High-Rise Jeans', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Cashmere Blend Scarf', 'cashmere-blend-scarf', 'A soft cashmere-blend scarf for winter.', 'Meridian', 45.00, 'EUR', 38, 0.12, 4.80, 51)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Camel'), (p, 'material', 'Cashmere Blend');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Cashmere Blend Scarf', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Everyday Sneakers', 'everyday-sneakers-womens', 'Lightweight everyday sneakers with cushioned soles.', 'Fieldstone', 65.00, 'EUR', 36, 0.54, 4.30, 121)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Blush'), (p, 'size', '38'), (p, 'material', 'Canvas');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Everyday Sneakers (Women''s)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Quilted Puffer Vest', 'quilted-puffer-vest', 'A packable puffer vest for layering.', 'Meridian', 69.00, 'EUR', 0, 0.31, 4.10, 29)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Black'), (p, 'size', 'M'), (p, 'material', 'Recycled Polyester');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Quilted Puffer Vest', 0);

    -- ── Books ───────────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'The Quiet Harbor', 'the-quiet-harbor-hardcover', 'A novel about a small Estonian fishing town.', 'Baltic Press', 24.90, 'EUR', 20, 0.48, 4.60, 91)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Hardcover'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'The Quiet Harbor (Hardcover)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'Foundations of Databases', 'foundations-of-databases-paperback', 'An accessible introduction to relational database design.', 'Baltic Press', 24.90, 'EUR', 41, 0.44, 4.70, 60)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Paperback'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'Foundations of Databases (Paperback)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'Tides of Tallinn', 'tides-of-tallinn', 'A historical drama set in 16th-century Tallinn.', 'Baltic Press', 17.90, 'EUR', 33, 0.36, 4.40, 58)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Paperback'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'Tides of Tallinn', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'The Beginner''s Kitchen', 'the-beginners-kitchen', 'An approachable cookbook for first-time cooks.', 'Hearth Books', 22.90, 'EUR', 47, 0.72, 4.80, 134)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Hardcover'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'The Beginner''s Kitchen', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'Growing Wild: A Garden Year', 'growing-wild-a-garden-year', 'A season-by-season guide to a low-maintenance garden.', 'Hearth Books', 19.90, 'EUR', 29, 0.51, 4.50, 42)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Paperback'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'Growing Wild: A Garden Year', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'Databases at Scale', 'databases-at-scale', 'A practitioner''s guide to scaling relational systems.', 'Baltic Press', 39.90, 'EUR', 0, 0.79, 4.90, 27)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Hardcover'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'Databases at Scale', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'The Long Coastline', 'the-long-coastline', 'A travel memoir along the Baltic coast.', 'Baltic Press', 16.90, 'EUR', 18, 0.34, 4.30, 19)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Paperback'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'The Long Coastline', 0);

    -- ── Home & Garden ────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Ceramic Pour-Over Set', 'ceramic-pour-over-set-blue', 'A hand-glazed ceramic pour-over coffee set.', 'Hearthware', 39.00, 'EUR', 26, 0.95, 4.80, 40)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Slate Blue'), (p, 'material', 'Ceramic');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Ceramic Pour-Over Set (Blue)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Raised Garden Bed Kit', 'raised-garden-bed-kit-large', 'A modular cedar raised bed for home gardening.', 'Hearthware', 179.00, 'EUR', 9, 24.6, 4.10, 15)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'material', 'Cedar'), (p, 'color', 'Natural'), (p, 'size', 'Large');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Raised Garden Bed Kit (Large)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Cast Iron Skillet', 'cast-iron-skillet', 'A pre-seasoned 30cm cast iron skillet.', 'Hearthware', 45.00, 'EUR', 62, 2.4, 4.90, 205)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'material', 'Cast Iron'), (p, 'size', '30cm');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Cast Iron Skillet', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Linen Throw Blanket', 'linen-throw-blanket', 'A breathable stonewashed linen throw.', 'Hearthware', 55.00, 'EUR', 34, 0.85, 4.60, 48)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Oatmeal'), (p, 'material', 'Linen');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Linen Throw Blanket', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Solar Garden Lights (Set of 6)', 'solar-garden-lights-set-of-6', 'Weatherproof solar path lights, no wiring needed.', 'Hearthware', 32.00, 'EUR', 71, 1.1, 4.20, 88)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Black'), (p, 'material', 'Aluminum');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Solar Garden Lights', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Bamboo Cutting Board Set', 'bamboo-cutting-board-set', 'A set of 3 sustainably-sourced bamboo boards.', 'Hearthware', 28.00, 'EUR', 0, 1.3, 4.40, 63)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'material', 'Bamboo');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Bamboo Cutting Board Set', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Ceramic Planter Trio', 'ceramic-planter-trio', 'Three nesting ceramic planters with drainage.', 'Hearthware', 36.00, 'EUR', 45, 1.6, 4.70, 37)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'White'), (p, 'material', 'Ceramic');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Ceramic Planter Trio', 0);
END $$;
