package ee.shop.catalog.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests — metric is the only stored unit (Product's javadoc);
 * these conversions are what stand between a correct listing and a product
 * page silently showing the wrong weight/dimensions in imperial.
 */
@Tag("unit")
class UnitConverterTest {

    @Test
    void kgToLb_convertsAtStandardFactor() {
        // 1.28 kg (the seeded Vantage 14 Ultrabook) -> ~2.82 lb.
        assertThat(UnitConverter.kgToLb(new BigDecimal("1.28")))
                .isEqualByComparingTo(new BigDecimal("2.82"));
    }

    @Test
    void cmToIn_convertsAtStandardFactor() {
        // 31.8 cm -> ~12.52 in.
        assertThat(UnitConverter.cmToIn(new BigDecimal("31.8")))
                .isEqualByComparingTo(new BigDecimal("12.52"));
    }

    @Test
    void kgToLb_ofNull_returnsNull() {
        assertThat(UnitConverter.kgToLb(null)).isNull();
    }

    @Test
    void cmToIn_ofNull_returnsNull() {
        assertThat(UnitConverter.cmToIn(null)).isNull();
    }

    @Test
    void kgToLb_ofZero_isZero() {
        assertThat(UnitConverter.kgToLb(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void conversions_areRoundedToTwoDecimalPlaces() {
        BigDecimal result = UnitConverter.kgToLb(new BigDecimal("18.4")); // raised garden bed kit
        assertThat(result.scale()).isEqualTo(2);
    }
}
