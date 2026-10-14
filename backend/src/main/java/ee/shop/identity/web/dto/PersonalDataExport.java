package ee.shop.identity.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Everything the shop holds about one person, in a machine-readable form
 * (GDPR Art. 15 access, Art. 20 portability).
 *
 * Art. 20 asks for a "structured, commonly used and machine-readable format",
 * which is why this is JSON rather than a formatted email or a PDF.
 *
 * It deliberately includes the ORDERS, even though the customer can already see
 * them: an export that omits the largest category of personal data the shop
 * holds would not be an export. Order lines carry the snapshotted name and
 * price, not a live join to products (DECISIONS.md §2), so what comes out is
 * what was actually agreed at the time.
 */
public record PersonalDataExport(
        Instant exportedAt,
        Account account,
        List<Order> orders,
        List<Review> reviews
) {
    public record Account(
            String email,
            String displayName,
            String role,
            boolean emailVerified,
            boolean twoFactorEnabled,
            Instant createdAt
    ) {}

    /** Billing details are the snapshot ON the order, which is the data actually held. */
    public record Order(
            String reference,
            String status,
            Instant placedAt,
            String currency,
            String total,
            String billingName,
            String billingEmail,
            String billingPhone,
            String billingAddress,
            List<Line> lines
    ) {
        public record Line(String productName, int quantity, String unitPrice, String lineTotal) {}
    }

    public record Review(String productName, int rating, String title, String body, Instant createdAt) {}
}
