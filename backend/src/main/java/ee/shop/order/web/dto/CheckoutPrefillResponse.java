package ee.shop.order.web.dto;

/** Billing details from the caller's most recent order, offered to speed up a repeat checkout — never authoritative. */
public record CheckoutPrefillResponse(
        String billingName,
        String billingPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String postalCode,
        String country
) {}
