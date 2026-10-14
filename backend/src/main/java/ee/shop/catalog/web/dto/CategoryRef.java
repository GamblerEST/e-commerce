package ee.shop.catalog.web.dto;

import java.util.UUID;

public record CategoryRef(UUID id, String name, String slug) {}
