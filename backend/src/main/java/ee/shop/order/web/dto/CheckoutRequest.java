package ee.shop.order.web.dto;

import jakarta.validation.constraints.Email;
import ee.shop.common.validation.PostalCodeMatchesCountry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Deliberately carries ONLY billing details — never product ids, prices, or
 * quantities. Line items always come from the caller's own server-side
 * cart (OrderService resolves it independently); trusting client-supplied
 * pricing would let a request simply declare its own total.
 */
@PostalCodeMatchesCountry
public record CheckoutRequest(
        @NotBlank @Size(max = 200) String billingName,
        @NotBlank @Email @Size(max = 255) String billingEmail,
        // Permissive on purpose — international phone formats vary too much
        // (spaces, dashes, parens, an optional leading +) to validate more
        // strictly without rejecting real numbers. Digits plus common
        // separators only; length bounds a runaway/garbage input.
        @NotBlank @Size(min = 5, max = 30) @Pattern(regexp = "^[0-9+()\\-.\\s]+$",
                message = "must be a valid phone number") String billingPhone,
        // Permissive on purpose, same reasoning as phone — NOT requiring a
        // house number. A regex requiring one would reject real Estonian
        // rural/farmstead addresses, which Estonia's own Address Data
        // System (ADS, Maa-amet) legitimately identifies by PROPERTY NAME
        // alone with no street or number at all (e.g. "Männiku talu") —
        // confirmed against ADS docs, not a hypothetical. Real .ee stores
        // don't pattern-match this at all; they verify against ADS itself
        // via the In-ADS widget (geoportaal.maaamet.ee) — planned for
        // later as the actual "for accuracy" check.
        @NotBlank @Size(max = 200) String addressLine1,
        @Size(max = 200) String addressLine2,
        // Letters (incl. accented), spaces, hyphens, apostrophes and commas. The
        // comma is for rural Estonian addresses, which name the village and the
        // municipality ("Männiku küla, Märjamaa vald") because village names
        // repeat across municipalities. A city name never contains digits.
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "^[A-Za-z\\u00C0-\\u024F][A-Za-z\\u00C0-\\u024F' ,-]*$",
                message = "must be a valid city name") String city,
        @NotBlank @Size(min = 3, max = 20)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 -]*$",
                message = "must be a valid postal code") String postalCode,
        @NotBlank @Size(min = 2, max = 2)
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code") String country,

        /**
         * Chosen delivery method, by its stable code (see DeliveryOption).
         * Optional: a shop can sell something that isn't shipped, and orders
         * placed before delivery options existed have none — so omitting it
         * produces an order with no delivery cost rather than a 400.
         */
        @Size(max = 60) String deliveryCode,
        // The Estonian address register's id for this address, present only when
        // the customer picked a suggestion. The server re-checks the fields
        // against it (AddressLookupService.verifySelected); absent means a typed
        // address, validated the ordinary way.
        @Pattern(regexp = "^[A-Z]{2}[0-9]{8}$", message = "must be an address register id") String adsOid,
        // How the customer pays. Null means CARD, which is what checkout did before
        // bank links existed.
        PaymentMethod paymentMethod,
        // The bank for a BANK_LINK payment: one of the codes GET /api/payments/methods
        // lists, checked against that list before anything is reserved.
        @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must be a bank code") String bankCode
) {
    public enum PaymentMethod { CARD, BANK_LINK }
}
