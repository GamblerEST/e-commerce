package ee.shop.catalog.web.dto;

import java.util.List;
import java.util.Map;

/**
 * What's actually filterable for the given category (or the whole catalog,
 * if none is selected) — lets the frontend render real dropdowns instead of
 * asking users to guess valid brand/attribute values.
 */
public record FacetsResponse(List<String> brands, Map<String, List<String>> attributes) {}
