package ee.shop.order.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * status is DERIVED (folded order_events), never a stored column —
 * DECISIONS.md §1. paymentClientSecret is only ever populated on the
 * response CheckoutController returns right after creating a fresh
 * PaymentIntent — list/detail views (OrderService.toResponse) leave it
 * null; re-exposing a stale client secret for an already-resolved payment
 * makes no sense there. paymentRedirectUrl is the bank-link counterpart, the
 * customer's payment page at their bank, and is populated the same way.
 */
public record OrderResponse(
        UUID id,
        /**
         * Human-readable order number for emails, support calls and the
         * confirmation page. A pure PROJECTION of `id`, derived on read and
         * never stored — see reference(). It is deliberately NOT a second
         * identity: nothing looks an order up by it, so the fact that two
         * orders could in principle share a prefix is cosmetic, not a
         * correctness problem. Storing it would mean a second unique key to
         * keep in sync with the first, for no gain.
         */
        String reference,
        String status,
        List<OrderLineResponse> lines,
        List<OrderEventResponse> events,
        String currency,
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal total,
        /** Null when the order has no delivery method (see DeliverySnapshot). */
        DeliveryDetails delivery,
        String billingName,
        String billingEmail,
        String billingPhone,
        String billingAddressLine1,
        String billingAddressLine2,
        String billingCity,
        String billingPostalCode,
        String billingCountry,
        Instant createdAt,
        String paymentClientSecret,
        String paymentRedirectUrl
) {
    /** "ORD-3F2A9C1B" — short enough to read down a phone, derived from the id. */
    public static String referenceFor(UUID id) {
        return "ORD-" + id.toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public OrderResponse withPaymentClientSecret(String clientSecret) {
        return new OrderResponse(id, reference, status, lines, events, currency, subtotal, vatTotal, total,
                delivery, billingName, billingEmail, billingPhone, billingAddressLine1, billingAddressLine2,
                billingCity, billingPostalCode, billingCountry, createdAt, clientSecret, paymentRedirectUrl);
    }

    public OrderResponse withPaymentRedirectUrl(String redirectUrl) {
        return new OrderResponse(id, reference, status, lines, events, currency, subtotal, vatTotal, total,
                delivery, billingName, billingEmail, billingPhone, billingAddressLine1, billingAddressLine2,
                billingCity, billingPostalCode, billingCountry, createdAt, paymentClientSecret, redirectUrl);
    }
}
