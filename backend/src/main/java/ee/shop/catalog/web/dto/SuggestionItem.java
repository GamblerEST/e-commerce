package ee.shop.catalog.web.dto;

import java.util.UUID;

public record SuggestionItem(UUID id, String name, String slug) {}
