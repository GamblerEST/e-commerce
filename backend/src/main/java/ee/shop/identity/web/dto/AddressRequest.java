package ee.shop.identity.web.dto;

import ee.shop.common.validation.PostalCodeMatchesCountry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Bounds mirror CheckoutRequest's, so an address saved here is one that
 * checkout will accept. Two forms validating the same thing differently is how
 * you get a saved address that cannot be used to order.
 *
 * Note what is NOT validated: that the street exists. That needs the national
 * address registry (In-ADS), which is tracked separately — a regex cannot tell
 * a real Estonian address from a plausible one, and trying makes it reject real
 * rural addresses that have a name and no house number.
 */
@PostalCodeMatchesCountry
public record AddressRequest(
        @Size(max = 60) String label,

        @NotBlank @Size(min = 2, max = 120) String recipientName,

        @Size(max = 30) @Pattern(regexp = "^$|^[0-9+()\\-.\\s]+$",
                message = "must be a valid phone number") String phone,

        @NotBlank @Size(max = 200) String line1,
        @Size(max = 200) String line2,
        @NotBlank @Size(max = 120) String city,

        @NotBlank @Size(min = 3, max = 20)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 -]*$",
                message = "must be a valid postal code") String postalCode,

        @NotBlank @Pattern(regexp = "^[A-Z]{2}$",
                message = "must be a 2-letter country code") String country,

        boolean makeDefault,
        // The Estonian address register's id for this address, present only when
        // the customer picked a suggestion. The server re-checks the fields
        // against it (AddressLookupService.verifySelected); absent means a typed
        // address, validated the ordinary way.
        @Pattern(regexp = "^[A-Z]{2}[0-9]{8}$", message = "must be an address register id") String adsOid
) {}
