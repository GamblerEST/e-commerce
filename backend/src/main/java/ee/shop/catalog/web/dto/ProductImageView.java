package ee.shop.catalog.web.dto;

/**
 * One product image in every size the shop serves it (V21).
 *
 * All three URLs are always populated: where a rendition was not generated —
 * images seeded before renditions existed, vectors, or anything already
 * smaller than the target — the field carries the original. The client
 * therefore never has to check for null or construct a URL that might 404, and
 * a page always renders.
 *
 * altText is the admin-supplied description. It is preferred over anything the
 * frontend could compose from the product name, because only a human looking
 * at the photo knows whether it shows the product in use, a detail shot, or
 * the packaging.
 */
public record ProductImageView(
        /** Full size, exactly as uploaded — for zoom. */
        String url,
        /** ~1000px long edge, for the product page. */
        String mediumUrl,
        /** ~400px long edge, for grids, cart lines and related-product strips. */
        String thumbnailUrl,
        String altText
) {}
