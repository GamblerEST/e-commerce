package ee.shop.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.BeanWrapperImpl;

import java.util.Map;
import java.util.regex.Pattern;

public class PostalCodeMatchesCountryValidator
        implements ConstraintValidator<PostalCodeMatchesCountry, Object> {

    /**
     * Only the countries this shop ships to. Deliberately short: a table of
     * every country's postal format goes stale and starts rejecting valid
     * addresses, and a wrong rejection costs a sale while a missed check costs
     * one delivery attempt.
     */
    private static final Map<String, Pattern> BY_COUNTRY = Map.of(
            "EE", Pattern.compile("^\\d{5}$"),           // Estonia: 10123
            "FI", Pattern.compile("^\\d{5}$"),           // Finland
            "LV", Pattern.compile("^(LV-)?\\d{4}$"),     // Latvia: LV-1010 or 1010
            "LT", Pattern.compile("^(LT-)?\\d{5}$")      // Lithuania
    );

    private String postalCodeField;
    private String countryField;
    private String message;

    @Override
    public void initialize(PostalCodeMatchesCountry annotation) {
        this.postalCodeField = annotation.postalCode();
        this.countryField = annotation.country();
        this.message = annotation.message();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        var wrapper = new BeanWrapperImpl(value);
        Object postal = wrapper.getPropertyValue(postalCodeField);
        Object country = wrapper.getPropertyValue(countryField);

        // Blank/absent values are somebody else's constraint (@NotBlank).
        // Reporting them twice gives the customer two errors for one mistake.
        if (!(postal instanceof String p) || p.isBlank()
                || !(country instanceof String c) || c.isBlank()) {
            return true;
        }

        Pattern expected = BY_COUNTRY.get(c.toUpperCase());
        if (expected == null) {
            return true; // unknown country — see the field comment above
        }
        if (expected.matcher(p.trim()).matches()) {
            return true;
        }

        // Attach the violation to the postal code FIELD, not the whole object,
        // so the form highlights the input the customer has to fix.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        "EE".equalsIgnoreCase(c)
                                ? "Estonian postal codes are five digits, for example 10123"
                                : message)
                .addPropertyNode(postalCodeField)
                .addConstraintViolation();
        return false;
    }
}
