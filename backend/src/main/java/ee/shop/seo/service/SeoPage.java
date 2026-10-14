package ee.shop.seo.service;

import java.util.List;
import java.util.Map;

/**
 * Everything one public page needs filled into index.html before it leaves the
 * server.
 *
 * @param canonicalUrl   absolute; null for a page that must not be canonical
 * @param imageUrl       absolute; the Open Graph image, or null
 * @param structuredData schema.org objects, each rendered as its own JSON-LD block
 * @param bodyHtml       already-escaped markup that stands in for the app until
 *                       React replaces it
 */
public record SeoPage(
        int status,
        String title,
        String description,
        String canonicalUrl,
        boolean noindex,
        String imageUrl,
        List<Map<String, Object>> structuredData,
        String bodyHtml
) {}
