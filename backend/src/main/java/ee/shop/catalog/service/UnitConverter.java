package ee.shop.catalog.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Metric is the only stored unit (see Product's javadoc) — these are pure,
 * stateless conversions applied at the API layer so imperial values can
 * never drift from the metric source of truth.
 */
public final class UnitConverter {

    private static final BigDecimal KG_TO_LB = new BigDecimal("2.204623");
    private static final BigDecimal CM_TO_IN = new BigDecimal("0.393701");

    private UnitConverter() {}

    public static BigDecimal kgToLb(BigDecimal kg) {
        return kg == null ? null : kg.multiply(KG_TO_LB).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal cmToIn(BigDecimal cm) {
        return cm == null ? null : cm.multiply(CM_TO_IN).setScale(2, RoundingMode.HALF_UP);
    }
}
