package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Token-bucket limits, per IP+route or per IP+route AND
 * user+route (routes reachable by both guests and authenticated users —
 * see RateLimitService.enforceIpAndUser). Defaulted, unlike JWT_SECRET/
 * DB_URL — these are tunable operational knobs, not config whose absence
 * would silently misconfigure the box.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Limit register, Limit login, Limit refresh, Limit forgotPassword,
        Limit verifyEmail, Limit passwordReset,
        Limit checkout, Limit cartMutation, Limit productSearch, Limit contact,
        Limit addressLookup, Limit bankLinkReturn
) {
    public record Limit(int capacity, Duration period) {}
}
