package ee.shop.catalog.repository;

import ee.shop.catalog.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Merchandising reads — the queries that decide which products to PUT IN FRONT
 * of a shopper, as opposed to the ones they asked for.
 *
 * Home page (featured, collections) and the product page (related items) live
 * together because they share one projection and one shape of problem: pick a
 * small set, attach one image each, never show something unbuyable or deleted.
 *
 * Both are native queries using LATERAL, which has no JPQL equivalent — it is
 * the only way to say "and for each of these rows, the first image" without
 * either a per-row follow-up query (N+1) or fetching every image of every
 * product and discarding all but the first in Java.
 *
 * Bound to Product purely because Spring Data needs some managed entity to
 * hang the interface off; nothing here returns a Product.
 */
public interface HomeRepository extends JpaRepository<Product, UUID> {

    @Query(value = """
            SELECT p.id, p.name, p.slug, p.brand, p.price, p.currency,
                   p.stock_quantity AS stockQuantity,
                   p.avg_rating     AS avgRating,
                   p.review_count   AS reviewCount,
                   img.storage_key  AS thumbnailKey
              FROM products p
              LEFT JOIN LATERAL (
                    -- The small rendition where one exists (V21); the original
                    -- otherwise, so images seeded before renditions still show.
                    SELECT COALESCE(thumbnail_key, storage_key) AS storage_key
                      FROM product_images
                     WHERE product_id = p.id
                     ORDER BY display_order
                     LIMIT 1
              ) img ON true
             WHERE p.featured
               AND p.deleted_at IS NULL
             -- Curation decides WHICH products appear. Among those, anything
             -- actually buyable comes first: a shop window leading with a
             -- sold-out item spends its most valuable space on a dead end.
             -- Sold-out promoted products still appear, just below the rest,
             -- because hiding them entirely would make a temporary stockout
             -- look like the product was discontinued.
             ORDER BY (p.stock_quantity > 0) DESC, p.avg_rating DESC, p.created_at DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<FeaturedProductRow> findFeaturedProducts(@Param("limit") int limit);

    @Query(value = """
            SELECT c.id, c.name, c.slug, c.tagline,
                   stats.product_count AS productCount,
                   img.storage_key     AS thumbnailKey
              FROM categories c
              -- Products of a top-level collection usually hang off its
              -- CHILDREN (Electronics > Laptops), so counting only rows whose
              -- category_id is the collection itself would show 0 on a
              -- realistically-shaped catalogue. This counts the subtree one
              -- level deep, matching how V18 chose what to promote.
              -- NOTE: keep apostrophes out of these SQL comments. Spring Data
              -- scans the @Query string for quote characters before JPA ever
              -- sees it, so a single unpaired quote inside a comment reads as
              -- an unterminated string literal and fails at bean creation with
              -- a message that does not mention quoting at all.
              JOIN LATERAL (
                    SELECT count(*) AS product_count
                      FROM products p
                     WHERE p.deleted_at IS NULL
                       AND (p.category_id = c.id
                            OR p.category_id IN (SELECT id FROM categories WHERE parent_id = c.id))
              ) stats ON true
              LEFT JOIN LATERAL (
                    SELECT COALESCE(pi.thumbnail_key, pi.storage_key) AS storage_key
                      FROM products p
                      JOIN product_images pi ON pi.product_id = p.id
                     WHERE p.deleted_at IS NULL
                       AND (p.category_id = c.id
                            OR p.category_id IN (SELECT id FROM categories WHERE parent_id = c.id))
                     -- The primary image of the best-rated product stands in
                     -- as the cover, so a collection is never a blank tile and
                     -- the cover improves as the range does.
                     ORDER BY p.avg_rating DESC, pi.display_order
                     LIMIT 1
              ) img ON true
             WHERE c.featured
               AND stats.product_count > 0
             ORDER BY c.name
             LIMIT :limit
            """, nativeQuery = true)
    List<CollectionRow> findFeaturedCollections(@Param("limit") int limit);

    /**
     * "You might also like": the related/recommended products on a product
     * detail page.
     *
     * Same-category, best-rated, in-stock first. Deliberately NOT a
     * behavioural recommender: this shop has no purchase-history volume to
     * learn from, and a cold-start collaborative filter returns either noise
     * or the same global bestsellers on every page — which is worse than an
     * honest "more from this category", and far harder to explain.
     *
     * Falls back to the parent category when the product sits in a thin
     * subcategory, so a PDP is never left with an empty strip.
     */
    @Query(value = """
            SELECT p.id, p.name, p.slug, p.brand, p.price, p.currency,
                   p.stock_quantity AS stockQuantity,
                   p.avg_rating     AS avgRating,
                   p.review_count   AS reviewCount,
                   img.storage_key  AS thumbnailKey
              FROM products p
              LEFT JOIN LATERAL (
                    -- The small rendition where one exists (V21); the original
                    -- otherwise, so images seeded before renditions still show.
                    SELECT COALESCE(thumbnail_key, storage_key) AS storage_key
                      FROM product_images
                     WHERE product_id = p.id
                     ORDER BY display_order
                     LIMIT 1
              ) img ON true
             WHERE p.deleted_at IS NULL
               AND p.id <> :productId
               AND (
                     p.category_id = :categoryId
                     -- Siblings under the same parent, so a product in a
                     -- narrow subcategory still has neighbours to show.
                     OR p.category_id IN (
                          SELECT id FROM categories
                           WHERE parent_id = (SELECT parent_id FROM categories WHERE id = :categoryId)
                             AND parent_id IS NOT NULL
                     )
                   )
             ORDER BY (p.category_id = :categoryId) DESC,
                      (p.stock_quantity > 0) DESC,
                      p.avg_rating DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<FeaturedProductRow> findRelatedProducts(@Param("productId") UUID productId,
                                                 @Param("categoryId") UUID categoryId,
                                                 @Param("limit") int limit);

    interface FeaturedProductRow {
        UUID getId();
        String getName();
        String getSlug();
        String getBrand();
        BigDecimal getPrice();
        String getCurrency();
        int getStockQuantity();
        BigDecimal getAvgRating();
        int getReviewCount();
        String getThumbnailKey();
    }

    interface CollectionRow {
        UUID getId();
        String getName();
        String getSlug();
        String getTagline();
        long getProductCount();
        String getThumbnailKey();
    }
}
