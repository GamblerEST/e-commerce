package ee.shop.catalog.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The admin-editable attributes of a product, in one value object.
 *
 * Exists so Product's constructor and update() don't take thirteen
 * positional arguments each — a shape where transposing `widthCm` and
 * `heightCm` compiles fine and ships a wrong parcel size. It deliberately
 * carries ONLY what an admin may set: id, timestamps, avg_rating and
 * review_count are all owned elsewhere (the last two by a database trigger
 * over the review rows — V15__reviews.sql), so they cannot be passed here
 * even by accident.
 */
public record ProductSpec(
        UUID categoryId,
        String name,
        String slug,
        String description,
        String brand,
        BigDecimal price,
        String currency,
        String vatCategory,
        int stockQuantity,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,

        /**
         * Home-page promotion. Curated by an admin rather than derived from
         * sales or rating — see V18__home_featured.sql for why a computed
         * "featured" cannot serve the commercial purpose of a home page.
         */
        boolean featured
) {}
