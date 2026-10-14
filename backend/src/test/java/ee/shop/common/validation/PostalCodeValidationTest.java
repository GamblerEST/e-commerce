package ee.shop.common.validation;

import ee.shop.identity.web.dto.AddressRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postal codes are checked against the country they claim to be in.
 *
 * The generic rule this replaced accepted "ABC" and "1" as Estonian postal
 * codes. A wrong code means a parcel that does not arrive, discovered days
 * later rather than at the moment the customer could have fixed it.
 */
@Tag("unit")
@Tag("security")
class PostalCodeValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private AddressRequest address(String postalCode, String country) {
        return new AddressRequest("Home", "Kadri Tamm", "+372 5555 1234",
                "Pikk tn 1", null, "Tallinn", postalCode, country, false, null);
    }

    private boolean postalCodeRejected(String postalCode, String country) {
        return validator.validate(address(postalCode, country)).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("postalCode"));
    }

    @Test
    void estonianCodesMustBeFiveDigits() {
        assertThat(postalCodeRejected("10123", "EE")).isFalse();

        assertThat(postalCodeRejected("ABC", "EE")).isTrue();
        assertThat(postalCodeRejected("1", "EE")).isTrue();
        assertThat(postalCodeRejected("101234", "EE")).isTrue();
    }

    @Test
    void theEstonianMessageSaysWhatIsExpected() {
        var violation = validator.validate(address("nope", "EE")).stream()
                .filter(v -> v.getPropertyPath().toString().equals("postalCode"))
                .findFirst().orElseThrow();

        // "is not valid" tells a customer nothing they can act on.
        assertThat(violation.getMessage()).contains("five digits").contains("10123");
    }

    @Test
    void neighbouringCountriesUseTheirOwnFormats() {
        assertThat(postalCodeRejected("00100", "FI")).isFalse();
        assertThat(postalCodeRejected("LV-1010", "LV")).isFalse();
        assertThat(postalCodeRejected("1010", "LV")).isFalse();
        assertThat(postalCodeRejected("LT-01100", "LT")).isFalse();

        assertThat(postalCodeRejected("1010", "FI")).isTrue();
    }

    @Test
    void anUnknownCountryIsNotGuessedAt() {
        // Refusing an address because we do not know its postal format is the
        // more expensive mistake — it costs a sale, where a missed check costs
        // one delivery attempt.
        assertThat(postalCodeRejected("SW1A 1AA", "GB")).isFalse();
        assertThat(postalCodeRejected("whatever", "JP")).isFalse();
    }

    @Test
    void aBlankCodeIsLeftToTheExistingConstraints() {
        // A blank field already trips @NotBlank, @Size and @Pattern. This
        // validator must not pile a fourth message on top of a mistake the
        // customer has already been told about — "required" is the useful
        // message there, not "Estonian postal codes are five digits".
        //
        // (The API returns one message per field, so only one reaches the
        // customer either way; the point is which one.)
        boolean addedOurOwn = validator.validate(address("", "EE")).stream()
                .anyMatch(v -> v.getMessage().contains("five digits"));

        assertThat(addedOurOwn).isFalse();
    }
}
