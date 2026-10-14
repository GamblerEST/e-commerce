-- V4__catalog_seed.sql
-- Demo catalog data so search/facets/sort/pagination have something real to
-- return. There is no admin product-creation flow yet, so for now the
-- catalog is seeded here rather than through a public write endpoint that
-- would have no auth to gate it safely.

DO $$
DECLARE
    cat_electronics UUID;
    cat_phones      UUID;
    cat_laptops     UUID;
    cat_clothing    UUID;
    cat_mens        UUID;
    cat_womens      UUID;
    cat_books       UUID;
    cat_home        UUID;
    p UUID;
BEGIN
    INSERT INTO categories (name, slug) VALUES ('Electronics', 'electronics') RETURNING id INTO cat_electronics;
    INSERT INTO categories (name, slug, parent_id) VALUES ('Phones', 'phones', cat_electronics) RETURNING id INTO cat_phones;
    INSERT INTO categories (name, slug, parent_id) VALUES ('Laptops', 'laptops', cat_electronics) RETURNING id INTO cat_laptops;

    INSERT INTO categories (name, slug) VALUES ('Clothing', 'clothing') RETURNING id INTO cat_clothing;
    INSERT INTO categories (name, slug, parent_id) VALUES ('Men''s', 'mens', cat_clothing) RETURNING id INTO cat_mens;
    INSERT INTO categories (name, slug, parent_id) VALUES ('Women''s', 'womens', cat_clothing) RETURNING id INTO cat_womens;

    INSERT INTO categories (name, slug) VALUES ('Books', 'books') RETURNING id INTO cat_books;
    INSERT INTO categories (name, slug) VALUES ('Home & Garden', 'home-garden') RETURNING id INTO cat_home;

    -- ── Phones ──────────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Aurora X12 Smartphone', 'aurora-x12-smartphone', 'A capable mid-range smartphone with a 6.5" display.', 'Aurora', 429.00, 'EUR', 42, 0.189, 4.30, 128)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Midnight Black'), (p, 'storage', '128GB'), (p, 'screen_size', '6.5"');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Aurora X12 Smartphone', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Aurora X12 Smartphone', 'aurora-x12-smartphone-silver', 'A capable mid-range smartphone with a 6.5" display.', 'Aurora', 429.00, 'EUR', 17, 0.189, 4.10, 64)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Silver'), (p, 'storage', '256GB'), (p, 'screen_size', '6.5"');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Aurora X12 Smartphone (Silver)', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_phones, 'Northline Pocket Phone', 'northline-pocket-phone', 'A compact, affordable phone for everyday use.', 'Northline', 199.00, 'EUR', 0, 0.152, 3.80, 41)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Blue'), (p, 'storage', '64GB');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Northline Pocket Phone', 0);

    -- ── Laptops ─────────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, length_cm, width_cm, height_cm, avg_rating, review_count)
        VALUES (cat_laptops, 'Vantage 14 Ultrabook', 'vantage-14-ultrabook', 'A light 14" ultrabook built for travel.', 'Vantage', 1099.00, 'EUR', 15, 1.28, 31.8, 21.5, 1.5, 4.60, 89)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Space Gray'), (p, 'ram', '16GB'), (p, 'storage', '512GB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Vantage 14 Ultrabook', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_laptops, 'Workhorse 15 Pro', 'workhorse-15-pro', 'A 15" laptop built for demanding workloads.', 'Vantage', 1599.00, 'EUR', 6, 1.9, 4.80, 33)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Silver'), (p, 'ram', '32GB'), (p, 'storage', '1TB SSD');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-electronics.svg', 'Workhorse 15 Pro', 0);

    -- ── Men's clothing ──────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Classic Crew T-Shirt', 'classic-crew-tshirt-m', 'A soft, breathable everyday t-shirt.', 'Fieldstone', 19.90, 'EUR', 120, 0.18, 4.20, 210)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Navy'), (p, 'size', 'M'), (p, 'material', 'Cotton');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Classic Crew T-Shirt', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Classic Crew T-Shirt', 'classic-crew-tshirt-l', 'A soft, breathable everyday t-shirt.', 'Fieldstone', 19.90, 'EUR', 95, 0.19, 4.20, 210)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Navy'), (p, 'size', 'L'), (p, 'material', 'Cotton');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Classic Crew T-Shirt', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_mens, 'Weekend Denim Jacket', 'weekend-denim-jacket', 'A durable denim jacket for cooler days.', 'Fieldstone', 79.00, 'EUR', 30, 0.85, 4.50, 56)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Indigo'), (p, 'size', 'M'), (p, 'material', 'Denim');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Weekend Denim Jacket', 0);

    -- ── Women's clothing ────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Everyday Wrap Dress', 'everyday-wrap-dress', 'A flattering wrap dress for any occasion.', 'Meridian', 59.00, 'EUR', 48, 0.32, 4.70, 143)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Emerald'), (p, 'size', 'S'), (p, 'material', 'Viscose');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Everyday Wrap Dress', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_womens, 'Trail Running Jacket', 'trail-running-jacket', 'A lightweight, packable running jacket.', 'Meridian', 89.00, 'EUR', 22, 0.28, 4.40, 77)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Coral'), (p, 'size', 'M'), (p, 'material', 'Recycled Polyester');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-clothing.svg', 'Trail Running Jacket', 0);

    -- ── Books ───────────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'The Quiet Harbor', 'the-quiet-harbor', 'A novel about a small Estonian fishing town.', 'Baltic Press', 14.90, 'EUR', 60, 0.32, 4.60, 91)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Paperback'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'The Quiet Harbor', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_books, 'Foundations of Databases', 'foundations-of-databases', 'An accessible introduction to relational database design.', 'Baltic Press', 34.90, 'EUR', 25, 0.68, 4.80, 152)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'format', 'Hardcover'), (p, 'language', 'English');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-books.svg', 'Foundations of Databases', 0);

    -- ── Home & Garden ────────────────────────────────────────────────────────
    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Ceramic Pour-Over Set', 'ceramic-pour-over-set', 'A hand-glazed ceramic pour-over coffee set.', 'Hearthware', 39.00, 'EUR', 34, 0.95, 4.90, 66)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'color', 'Terracotta'), (p, 'material', 'Ceramic');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Ceramic Pour-Over Set', 0);

    INSERT INTO products (category_id, name, slug, description, brand, price, currency, stock_quantity, weight_kg, avg_rating, review_count)
        VALUES (cat_home, 'Raised Garden Bed Kit', 'raised-garden-bed-kit', 'A modular cedar raised bed for home gardening.', 'Hearthware', 129.00, 'EUR', 0, 18.4, 3.90, 21)
        RETURNING id INTO p;
    INSERT INTO product_attributes (product_id, attribute_name, attribute_value) VALUES
        (p, 'material', 'Cedar'), (p, 'color', 'Natural');
    INSERT INTO product_images (product_id, storage_key, alt_text, display_order)
        VALUES (p, 'product-images/placeholder-home.svg', 'Raised Garden Bed Kit', 0);
END $$;
