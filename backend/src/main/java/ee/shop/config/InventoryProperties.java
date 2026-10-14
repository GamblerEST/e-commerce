package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable operational knobs (DECISIONS.md §3), not security-critical
 * secrets — defaulted like RateLimitProperties, unlike JWT_SECRET/DB_URL.
 *
 * reservationTtl is the entire point of the on_hand/reserved split: too
 * short abandons an honest, slow checkout; too long lets an abandoned cart
 * lock stock behind nothing. sweepInterval controls how promptly an expired
 * reservation's stock becomes available again for the next shopper.
 */
@ConfigurationProperties(prefix = "app.inventory")
public record InventoryProperties(Duration reservationTtl, Duration sweepInterval) {}
