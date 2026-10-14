package ee.shop.catalog.web.dto;

import ee.shop.catalog.model.ProductSpec;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Admin create/update payload for a product.
 *
 * Bounds mirror V3__catalog.sql's column types and CHECK constraints so a
 * bad value fails as a 400 with a named field rather than a 500 from the
 * database. In particular @Digits matches NUMERIC(12,2) exactly — without
 * it, a price of 1.999 would silently round on insert instead of being
 * rejected, and money must never be silently reinterpreted.
 *
 * There is no avgRating/reviewCount/id field on purpose: those are not an
 * admin's to set (see ProductSpec).
 */
public record AdminProductRequest(
        UUID categoryId,

        @NotBlank @Size(max = 200) String name,

        // Lowercase, digits and hyphens — a slug is a URL path segment, and
        // anything else here either breaks the URL or needs escaping at
        // every render site.
        @NotBlank @Size(max = 200)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "must be lowercase letters, digits and hyphens") String slug,

        @Size(max = 5000) String description,
        @Size(max = 120) String brand,

        @NotNull @DecimalMin(value = "0.00", inclusive = true)
        @DecimalMax(value = "9999999999.99")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places") BigDecimal price,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$",
                message = "must be a 3-letter ISO-4217 currency code") String currency,

        // Must be a key the tax configuration actually knows; the service
        // checks it against TaxProperties rather than hardcoding the list
        // here, since VAT categories are configuration (DECISIONS.md §4).
        @NotBlank @Size(max = 30) String vatCategory,

        @Min(0) int stockQuantity,

        @DecimalMin("0.000") @Digits(integer = 6, fraction = 3) BigDecimal weightKg,
        @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal lengthCm,
        @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal widthCm,
        @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal heightCm,

        /**
         * Home-page promotion. BOXED Boolean, not primitive: a primitive here
         * makes the field effectively REQUIRED to Jackson, so every existing
         * client that does not send it — the bulk importer, the API tests, any
         * integration written before this field existed — starts failing with
         * a 400 "Malformed or unexpected request body" that names nothing.
         * Null means "not featured", resolved in toSpec().
         */
        Boolean featured
) {
    public ProductSpec toSpec() {
        // description is NOT NULL DEFAULT '' in the schema, and a DEFAULT
        // only applies when the column is omitted — sending an explicit NULL
        // fails the constraint instead. An omitted description is a legitimate
        // draft state, so it becomes an empty string rather than a 400.
        return new ProductSpec(categoryId, name, slug, description == null ? "" : description,
                brand, price, currency, vatCategory, stockQuantity,
                weightKg, lengthCm, widthCm, heightCm, featured != null && featured);
    }
}
