package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * VAT rates, by category — NEVER a hardcoded constant (DECISIONS.md
 * §4: Estonia's standard rate moved 20% -> 22% -> 24% inside 18 months). The
 * category a product belongs to (products.vat_category) is stable law; the
 * RATE for that category is what changes and lives here instead.
 */
@ConfigurationProperties(prefix = "app.tax")
public record TaxProperties(String defaultCountry, Map<String, BigDecimal> rates) {

    public BigDecimal rateFor(String vatCategory) {
        BigDecimal rate = rates.get(vatCategory);
        if (rate == null) {
            throw new IllegalStateException("No VAT rate configured for category: " + vatCategory);
        }
        return rate;
    }
}
