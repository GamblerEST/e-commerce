-- V8__catalog_sample_photos.sql
-- One product per leaf category gets a real bundled sample photo (not a
-- flat-color placeholder) so anyone browsing the catalog sees at least
-- one genuine image per category out of the box, on any machine, with no
-- manual upload step required. These are committed static assets
-- (src/main/resources/static/product-images/samples/), NOT rows pointing at
-- product-images/uploads/** — that path is a runtime-only Docker volume,
-- local to whichever machine actually called the upload endpoint, and would
-- be broken/missing on a fresh clone. The upload endpoint itself
-- (ProductImageController) is still real and still tested independently of
-- this seed choice.
--
-- Matched by slug, not name — several seeded products share a display name
-- across size/color variants (e.g. multiple "Classic Crew T-Shirt" rows with
-- distinct slugs), so name alone is not a safe unique key here.

UPDATE product_images SET storage_key = 'product-images/samples/sample-phone.jpg'
    WHERE product_id = (SELECT id FROM products WHERE slug = 'aurora-flagship-mini');

UPDATE product_images SET storage_key = 'product-images/samples/sample-laptop.jpg'
    WHERE product_id = (SELECT id FROM products WHERE slug = 'aurora-book-flex');

UPDATE product_images SET storage_key = 'product-images/samples/sample-mens.jpg'
    WHERE product_id = (SELECT id FROM products WHERE slug = 'classic-crew-tshirt-m');

UPDATE product_images SET storage_key = 'product-images/samples/sample-womens.jpg'
    WHERE product_id = (SELECT id FROM products WHERE slug = 'cashmere-blend-scarf');

UPDATE product_images SET storage_key = 'product-images/samples/sample-book.jpg'
    WHERE product_id = (SELECT id FROM products WHERE slug = 'databases-at-scale');

UPDATE product_images SET storage_key = 'product-images/samples/sample-home.jpg'
    WHERE product_id = (SELECT id FROM products WHERE slug = 'bamboo-cutting-board-set');
