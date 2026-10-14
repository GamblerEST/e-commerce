package ee.shop.catalog.web.dto;

import java.util.UUID;

/**
 * A category as both the public nav and the admin catalogue screen read it.
 *
 * featured/tagline are included rather than kept admin-only: they are not
 * sensitive (the home page already shows exactly which categories are
 * promoted, to everyone), and splitting them into a separate admin DTO would
 * mean the admin screen needs two calls to render one table.
 */
public record CategoryResponse(UUID id, String name, String slug, UUID parentId,
                               boolean featured, String tagline) {}
