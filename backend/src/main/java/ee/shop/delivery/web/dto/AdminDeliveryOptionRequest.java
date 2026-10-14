package ee.shop.delivery.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Bounds mirror V17__delivery_options.sql's CHECK constraints, so a bad
 * value is a 400 naming the field rather than a 500 from the database.
 * @Digits matches NUMERIC(12,2) exactly — money must never be silently
 * re-rounded on the way in.
 *
 * The min ≤ max relationship is checked in the service, not here: a
 * cross-field rule can't be expressed by a single field annotation, and
 * leaving it to the database CHECK alone would surface as a 500.
 */
public record AdminDeliveryOptionRequest(
        /** Only used on create — the code is the stable key orders snapshot, so it never changes. */
        @NotBlank @Size(max = 60)
        @Pattern(regexp = "^[a-z0-9]+(?:_[a-z0-9]+)*$",
                message = "must be lowercase letters, digits and underscores") String code,

        @NotBlank @Size(max = 120) String name,
        @Size(max = 120) String carrier,

        @NotNull @DecimalMin("0.00")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places") BigDecimal price,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$",
                message = "must be a 3-letter ISO-4217 currency code") String currency,

        @Min(0) int leadTimeDaysMin,
        @Min(0) int leadTimeDaysMax,

        boolean active,
        int sortOrder
) {}
