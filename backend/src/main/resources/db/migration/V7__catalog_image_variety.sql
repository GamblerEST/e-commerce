-- V7__catalog_image_variety.sql
-- V4/V5 seeded every product with one of 4 top-level-category placeholder
-- SVGs, so Phones and Laptops (both "Electronics") looked identical, and
-- Men's/Women's (both "Clothing") looked identical. Repoints each product's
-- existing image row at a leaf-category-specific placeholder instead — 6
-- distinct images across the 6 leaf categories products actually use,
-- rather than 4 across two levels of the tree. Books and Home & Garden
-- already had their own dedicated placeholder, so they're untouched.

UPDATE product_images
   SET storage_key = 'product-images/placeholder-phones.svg'
 WHERE storage_key = 'product-images/placeholder-electronics.svg'
   AND product_id IN (SELECT id FROM products WHERE category_id = (SELECT id FROM categories WHERE slug = 'phones'));

UPDATE product_images
   SET storage_key = 'product-images/placeholder-laptops.svg'
 WHERE storage_key = 'product-images/placeholder-electronics.svg'
   AND product_id IN (SELECT id FROM products WHERE category_id = (SELECT id FROM categories WHERE slug = 'laptops'));

UPDATE product_images
   SET storage_key = 'product-images/placeholder-mens.svg'
 WHERE storage_key = 'product-images/placeholder-clothing.svg'
   AND product_id IN (SELECT id FROM products WHERE category_id = (SELECT id FROM categories WHERE slug = 'mens'));

UPDATE product_images
   SET storage_key = 'product-images/placeholder-womens.svg'
 WHERE storage_key = 'product-images/placeholder-clothing.svg'
   AND product_id IN (SELECT id FROM products WHERE category_id = (SELECT id FROM categories WHERE slug = 'womens'));
