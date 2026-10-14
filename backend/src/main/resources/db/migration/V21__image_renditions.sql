-- V21__image_renditions.sql
-- Product images stored and served in multiple sizes: thumbnails alongside
-- the full-size original.
--
-- Why columns rather than a naming convention
-- ───────────────────────────────────────────
-- The cheap approach is to derive a thumbnail URL from the original by string
-- manipulation ("foo.jpg" -> "foo-thumb.jpg"). That breaks the moment an image
-- has no rendition — which is true of every image seeded before this migration
-- — and the failure is a broken <img> on the shop floor, not an error anyone
-- sees in a log.
--
-- Recording the keys instead means the API always returns a URL that exists.
-- An image with no rendition simply reports its original for every size, so
-- old rows keep working and nothing has to guess.
ALTER TABLE product_images ADD COLUMN thumbnail_key TEXT;
ALTER TABLE product_images ADD COLUMN medium_key    TEXT;

COMMENT ON COLUMN product_images.thumbnail_key IS
    'Small rendition for grids and cart lines. NULL means none was generated (pre-V21 rows, or SVG); callers fall back to storage_key.';
COMMENT ON COLUMN product_images.medium_key IS
    'Mid-size rendition for product detail. NULL falls back to storage_key.';
