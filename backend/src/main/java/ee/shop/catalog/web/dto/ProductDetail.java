package ee.shop.catalog.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProductDetail(
        UUID id,
        String name,
        String slug,
        String description,
        String brand,
        BigDecimal price,
        String currency,
        int stockQuantity,
        BigDecimal avgRating,
        int reviewCount,
        CategoryRef category,
        Dimensions dimensions,
        /**
         * Kept as the FULL-SIZE list so existing callers are unaffected. New
         * work should read `images`, which carries every rendition plus the
         * admin's alt text.
         */
        List<String> imageUrls,
        List<ProductImageView> images,
        Map<String, List<String>> attributes
) {}
