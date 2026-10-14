package ee.shop.catalog.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSummary(
        UUID id,
        String name,
        String slug,
        String brand,
        BigDecimal price,
        String currency,
        int stockQuantity,
        BigDecimal avgRating,
        int reviewCount,
        String thumbnailUrl
) {}
