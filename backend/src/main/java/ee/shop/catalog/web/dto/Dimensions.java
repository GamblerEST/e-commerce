package ee.shop.catalog.web.dto;

import java.math.BigDecimal;

/**
 * Computed from the product's canonical metric columns — never stored
 * separately, so metric and imperial can never drift apart. See Product's
 * javadoc.
 */
public record Dimensions(
        BigDecimal weightKg, BigDecimal weightLb,
        BigDecimal lengthCm, BigDecimal lengthIn,
        BigDecimal widthCm, BigDecimal widthIn,
        BigDecimal heightCm, BigDecimal heightIn
) {}
