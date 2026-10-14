package ee.shop.catalog.web.dto;

import java.util.UUID;

/**
 * A promoted category as the home page renders it.
 *
 * productCount and thumbnailUrl are derived at read time rather than stored:
 * both change every time a product is added, retired or re-photographed, and a
 * cached copy would show a "42 items" tile linking to a collection holding 3.
 */
public record CollectionSummary(
        UUID id,
        String name,
        String slug,
        String tagline,
        long productCount,
        String thumbnailUrl
) {}
