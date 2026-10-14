package ee.shop.catalog.web.dto;

import java.util.List;

/**
 * Everything the home page needs, in ONE round trip.
 *
 * Deliberately not two endpoints the client then joins: the home page is the
 * most-hit, most latency-sensitive page in the shop, and two sequential
 * fetches would put a second network round trip in front of first paint for
 * no benefit — nothing renders until both have landed anyway.
 */
public record HomeResponse(
        List<ProductSummary> featured,
        List<CollectionSummary> collections
) {}
