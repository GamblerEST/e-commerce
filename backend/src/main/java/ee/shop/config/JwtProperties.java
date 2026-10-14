package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Immutable, validated-at-startup config. Records not @Component beans:
 * config should be read-only after boot.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTtl,
        Duration refreshTtl
) {
    public JwtProperties {
        // Two SEPARATE checks, not one: requireResolved catches an unset
        // secret (including Spring silently binding an unresolved nested
        // placeholder literal — see RequiredSecrets) fast-failing before it
        // ever reaches the byte-length check below. The length check catches
        // a real-but-too-short value. This secret previously survived the
        // placeholder-literal failure mode only by accident (the literal
        // text happens to be under 32 bytes) — that's not something to keep
        // relying on.
        RequiredSecrets.requireResolved(secret, "app.jwt.secret", "Generate one with: openssl rand -base64 48");

        // Fail at STARTUP, not at first login. HS256 requires a key of at least
        // 256 bits; a shorter one makes forgery tractable. JJWT would throw at
        // the moment someone tries to log in — i.e. in production, at 2am.
        if (secret.getBytes().length < 32) {
            throw new IllegalStateException(
                "app.jwt.secret must be at least 32 bytes (256 bits). " +
                "Generate one with: openssl rand -base64 48");
        }
    }
}
