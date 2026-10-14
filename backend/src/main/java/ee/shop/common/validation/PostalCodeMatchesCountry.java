package ee.shop.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Checks a postal code against the country it claims to be in.
 *
 * The generic rule this replaces — "letters, digits, spaces and hyphens" —
 * accepts "ABC" and "1" as Estonian postal codes. Estonia's are exactly five
 * digits, and getting one wrong means a parcel that does not arrive, which the
 * customer discovers days later rather than at the point they could fix it.
 *
 * Only the countries this shop actually ships to are checked. An unrecognised
 * country falls back to the loose rule rather than rejecting it: guessing at
 * the postal format of somewhere we do not know is how a validator starts
 * refusing real addresses, which is the more expensive failure.
 */
@Documented
@Constraint(validatedBy = PostalCodeMatchesCountryValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PostalCodeMatchesCountry {
    String message() default "is not a valid postal code for that country";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /** Property name holding the postal code. */
    String postalCode() default "postalCode";
    /** Property name holding the ISO-3166-1 alpha-2 country code. */
    String country() default "country";
}
