package ee.shop.catalog.service;

import ee.shop.common.error.AppException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset (not OFFSET) pagination cursor. Encodes the
 * last row's sort value plus its id (the tie-breaker for rows sharing a sort
 * value), so the next page's WHERE clause is a direct index-friendly
 * comparison instead of a skip-and-count OFFSET that gets slower on every
 * deeper page and can skip/repeat rows if the result set changes between
 * requests.
 */
public record ProductCursor(String sortValue, UUID id) {

    public String encode() {
        String raw = sortValue + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ProductCursor decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int idx = decoded.lastIndexOf('|');
            return new ProductCursor(decoded.substring(0, idx), UUID.fromString(decoded.substring(idx + 1)));
        } catch (Exception e) {
            throw AppException.badRequest("Invalid pagination cursor");
        }
    }
}
