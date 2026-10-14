package ee.shop.catalog.web.dto;

import java.util.List;

/**
 * nextCursor is null when this page reached the end of the result set.
 * totalCount is the number of rows matching the current filters across ALL
 * pages, not just this one — lets the frontend render every page number
 * upfront instead of discovering pages one "next" click at a time.
 */
public record ProductSearchResponse(List<ProductSummary> items, String nextCursor, long totalCount) {}
